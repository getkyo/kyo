package kyo

/** Live LSP client handle managing one connection to an LSP server over a [[JsonRpcTransport]].
  *
  * Obtain via `LspClient.init` (Scope-managed) or `LspClient.initUnscoped` (manual close).
  * The client eagerly performs the LSP `initialize` / `initialized` handshake during `init`
  * before returning the handle (INV-061). The locked argument order for all `init` overloads is
  * `(transport, clientInfo, capabilities, handlers*)` per INV-060.
  *
  * INV-012: `LspClient = LspClient.Unsafe` (opaque identity).
  *
  * Typed methods for every server-handled request and notification are exposed as extension
  * methods. The `executeCommand[T]` method is typed-only per INV-064: no untyped variant exists.
  * The `getConfiguration[T]` method is on `LspServer` (the server issues it to the client);
  * the client sends `workspace/executeCommand` to the server instead.
  *
  * @see [[LspClient.init]]
  * @see [[LspClient.initUnscoped]]
  */
opaque type LspClient = LspClient.Unsafe

object LspClient:

    // =========================================================================
    // Scoped init quartet
    // =========================================================================

    /** Initializes an LSP client using default configuration and performs the handshake eagerly. */
    def init(
        transport: JsonRpcTransport,
        clientInfo: LspInfo,
        capabilities: LspCapabilities.Client.Client,
        handlers: LspHandler[?, ?, ?]*
    )(using Frame): LspClient < (Async & Scope & Abort[LspException]) =
        init(transport, clientInfo, capabilities, LspConfig.default, handlers*)

    /** Initializes an LSP client with explicit config and performs the handshake eagerly. */
    def init(
        transport: JsonRpcTransport,
        clientInfo: LspInfo,
        capabilities: LspCapabilities.Client.Client,
        config: LspConfig,
        handlers: LspHandler[?, ?, ?]*
    )(using Frame): LspClient < (Async & Scope & Abort[LspException]) =
        Scope.acquireRelease(
            internal.lsp.LspClientEngine.initClient(transport, clientInfo, capabilities, handlers, config)
        )(c => Sync.Unsafe.defer(c.close(Duration.Zero)(using AllowUnsafe.embrace.danger, Frame.internal).safe.get))

    /** Initializes and passes the client handle to the given function in a Scope context. */
    def initWith[A, S](
        transport: JsonRpcTransport,
        clientInfo: LspInfo,
        capabilities: LspCapabilities.Client.Client,
        handlers: LspHandler[?, ?, ?]*
    )(f: LspClient => A < S)(using Frame): A < (S & Async & Scope & Abort[LspException]) =
        init(transport, clientInfo, capabilities, LspConfig.default, handlers*).map(f)

    /** Initializes and passes the client handle to the given function in a Scope context with explicit config. */
    def initWith[A, S](
        transport: JsonRpcTransport,
        clientInfo: LspInfo,
        capabilities: LspCapabilities.Client.Client,
        config: LspConfig,
        handlers: LspHandler[?, ?, ?]*
    )(f: LspClient => A < S)(using Frame): A < (S & Async & Scope & Abort[LspException]) =
        init(transport, clientInfo, capabilities, config, handlers*).map(f)

    // =========================================================================
    // Unscoped init quartet
    // =========================================================================

    /** Initializes an LSP client without automatic resource cleanup. */
    def initUnscoped(
        transport: JsonRpcTransport,
        clientInfo: LspInfo,
        capabilities: LspCapabilities.Client.Client,
        handlers: LspHandler[?, ?, ?]*
    )(using Frame): LspClient < (Async & Abort[LspException]) =
        internal.lsp.LspClientEngine.initClient(transport, clientInfo, capabilities, handlers, LspConfig.default)

    /** Initializes an LSP client with config, without automatic resource cleanup. */
    def initUnscoped(
        transport: JsonRpcTransport,
        clientInfo: LspInfo,
        capabilities: LspCapabilities.Client.Client,
        config: LspConfig,
        handlers: LspHandler[?, ?, ?]*
    )(using Frame): LspClient < (Async & Abort[LspException]) =
        internal.lsp.LspClientEngine.initClient(transport, clientInfo, capabilities, handlers, config)

    /** Initializes unscoped and passes the client handle to the given function. */
    def initUnscopedWith[A, S](
        transport: JsonRpcTransport,
        clientInfo: LspInfo,
        capabilities: LspCapabilities.Client.Client,
        handlers: LspHandler[?, ?, ?]*
    )(f: LspClient => A < S)(using Frame): A < (S & Async & Abort[LspException]) =
        initUnscoped(transport, clientInfo, capabilities, LspConfig.default, handlers*).map(f)

    /** Initializes unscoped with explicit config and passes the client handle to the given function. */
    def initUnscopedWith[A, S](
        transport: JsonRpcTransport,
        clientInfo: LspInfo,
        capabilities: LspCapabilities.Client.Client,
        config: LspConfig,
        handlers: LspHandler[?, ?, ?]*
    )(f: LspClient => A < S)(using Frame): A < (S & Async & Abort[LspException]) =
        initUnscoped(transport, clientInfo, capabilities, config, handlers*).map(f)

    // =========================================================================
    // Extension methods (safe-tier bridge over Unsafe)
    // =========================================================================

    extension (self: LspClient)

        // textDocument requests

        def completion(params: LspHandler.CompletionParams)(using
            Frame
        ): Maybe[LspHandler.CompletionResult] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.completion(params).safe.get)

        def completionItemResolve(item: LspHandler.CompletionItem)(using
            Frame
        ): LspHandler.CompletionItem < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.completionItemResolve(item).safe.get)

        def hover(params: LspHandler.HoverParams)(using Frame): Maybe[LspHandler.Hover] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.hover(params).safe.get)

        def signatureHelp(params: LspHandler.SignatureHelpParams)(using
            Frame
        ): Maybe[LspHandler.SignatureHelp] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.signatureHelp(params).safe.get)

        def declaration(params: LspHandler.DeclarationParams)(using
            Frame
        ): Maybe[LspHandler.DeclarationResult] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.declaration(params).safe.get)

        def definition(params: LspHandler.DefinitionParams)(using
            Frame
        ): Maybe[LspHandler.DefinitionResult] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.definition(params).safe.get)

        def typeDefinition(params: LspHandler.TypeDefinitionParams)(using
            Frame
        ): Maybe[LspHandler.TypeDefinitionResult] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.typeDefinition(params).safe.get)

        def implementation(params: LspHandler.ImplementationParams)(using
            Frame
        ): Maybe[LspHandler.ImplementationResult] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.implementation(params).safe.get)

        def references(params: LspHandler.ReferenceParams)(using
            Frame
        ): Chunk[LspHandler.Location] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.references(params).safe.get)

        def documentHighlight(params: LspHandler.DocumentHighlightParams)(using
            Frame
        ): Chunk[LspHandler.DocumentHighlight] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.documentHighlight(params).safe.get)

        def documentSymbol(params: LspHandler.DocumentSymbolParams)(using
            Frame
        ): Maybe[LspHandler.DocumentSymbolResult] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.documentSymbol(params).safe.get)

        def codeAction(params: LspHandler.CodeActionParams)(using
            Frame
        ): Chunk[LspHandler.CommandOrCodeAction] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.codeAction(params).safe.get)

        def codeActionResolve(action: LspHandler.CodeAction)(using Frame): LspHandler.CodeAction < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.codeActionResolve(action).safe.get)

        def codeLens(params: LspHandler.CodeLensParams)(using Frame): Chunk[LspHandler.CodeLens] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.codeLens(params).safe.get)

        def codeLensResolve(lens: LspHandler.CodeLens)(using Frame): LspHandler.CodeLens < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.codeLensResolve(lens).safe.get)

        def documentLink(params: LspHandler.DocumentLinkParams)(using
            Frame
        ): Chunk[LspHandler.DocumentLink] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.documentLink(params).safe.get)

        def documentLinkResolve(link: LspHandler.DocumentLink)(using
            Frame
        ): LspHandler.DocumentLink < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.documentLinkResolve(link).safe.get)

        def documentColor(params: LspHandler.DocumentColorParams)(using
            Frame
        ): Chunk[LspHandler.ColorInformation] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.documentColor(params).safe.get)

        def colorPresentation(params: LspHandler.ColorPresentationParams)(using
            Frame
        ): Chunk[LspHandler.ColorPresentation] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.colorPresentation(params).safe.get)

        def formatting(params: LspHandler.DocumentFormattingParams)(using
            Frame
        ): Chunk[LspHandler.TextEdit] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.formatting(params).safe.get)

        def rangeFormatting(params: LspHandler.DocumentRangeFormattingParams)(using
            Frame
        ): Chunk[LspHandler.TextEdit] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.rangeFormatting(params).safe.get)

        def onTypeFormatting(params: LspHandler.DocumentOnTypeFormattingParams)(using
            Frame
        ): Chunk[LspHandler.TextEdit] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.onTypeFormatting(params).safe.get)

        def rename(params: LspHandler.RenameParams)(using Frame): Maybe[LspHandler.WorkspaceEdit] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.rename(params).safe.get)

        def prepareRename(params: LspHandler.PrepareRenameParams)(using
            Frame
        ): Maybe[LspHandler.PrepareRenameResult] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.prepareRename(params).safe.get)

        def foldingRange(params: LspHandler.FoldingRangeParams)(using
            Frame
        ): Chunk[LspHandler.FoldingRange] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.foldingRange(params).safe.get)

        def selectionRange(params: LspHandler.SelectionRangeParams)(using
            Frame
        ): Chunk[LspHandler.SelectionRange] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.selectionRange(params).safe.get)

        def linkedEditingRange(params: LspHandler.LinkedEditingRangeParams)(using
            Frame
        ): Maybe[LspHandler.LinkedEditingRanges] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.linkedEditingRange(params).safe.get)

        def prepareCallHierarchy(params: LspHandler.CallHierarchyPrepareParams)(using
            Frame
        ): Chunk[LspHandler.CallHierarchyItem] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.prepareCallHierarchy(params).safe.get)

        def callHierarchyIncomingCalls(params: LspHandler.CallHierarchyIncomingCallsParams)(using
            Frame
        ): Chunk[LspHandler.CallHierarchyIncomingCall] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.callHierarchyIncomingCalls(params).safe.get)

        def callHierarchyOutgoingCalls(params: LspHandler.CallHierarchyOutgoingCallsParams)(using
            Frame
        ): Chunk[LspHandler.CallHierarchyOutgoingCall] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.callHierarchyOutgoingCalls(params).safe.get)

        def prepareTypeHierarchy(params: LspHandler.TypeHierarchyPrepareParams)(using
            Frame
        ): Chunk[LspHandler.TypeHierarchyItem] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.prepareTypeHierarchy(params).safe.get)

        def typeHierarchySupertypes(params: LspHandler.TypeHierarchySupertypesParams)(using
            Frame
        ): Chunk[LspHandler.TypeHierarchyItem] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.typeHierarchySupertypes(params).safe.get)

        def typeHierarchySubtypes(params: LspHandler.TypeHierarchySubtypesParams)(using
            Frame
        ): Chunk[LspHandler.TypeHierarchyItem] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.typeHierarchySubtypes(params).safe.get)

        def semanticTokensFull(params: LspHandler.SemanticTokensParams)(using
            Frame
        ): Maybe[LspHandler.SemanticTokens] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.semanticTokensFull(params).safe.get)

        def semanticTokensFullDelta(params: LspHandler.SemanticTokensDeltaParams)(using
            Frame
        ): Maybe[LspHandler.SemanticTokensResult] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.semanticTokensFullDelta(params).safe.get)

        def semanticTokensRange(params: LspHandler.SemanticTokensRangeParams)(using
            Frame
        ): Maybe[LspHandler.SemanticTokens] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.semanticTokensRange(params).safe.get)

        def moniker(params: LspHandler.MonikerParams)(using Frame): Chunk[LspHandler.Moniker] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.moniker(params).safe.get)

        def inlayHint(params: LspHandler.InlayHintParams)(using
            Frame
        ): Chunk[LspHandler.InlayHint] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.inlayHint(params).safe.get)

        def inlayHintResolve(hint: LspHandler.InlayHint)(using Frame): LspHandler.InlayHint < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.inlayHintResolve(hint).safe.get)

        def inlineValue(params: LspHandler.InlineValueParams)(using
            Frame
        ): Chunk[LspHandler.InlineValue] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.inlineValue(params).safe.get)

        def documentDiagnostic(params: LspHandler.DocumentDiagnosticParams)(using
            Frame
        ): LspHandler.DocumentDiagnosticReport < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.documentDiagnostic(params).safe.get)

        def willSaveWaitUntil(params: LspHandler.WillSaveTextDocumentParams)(using
            Frame
        ): Chunk[LspHandler.TextEdit] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.willSaveWaitUntil(params).safe.get)

        // textDocument notifications

        def didOpen(params: LspHandler.DidOpenTextDocumentParams)(using Frame): Unit < (Async & Abort[Closed]) =
            Sync.Unsafe.defer(self.didOpen(params).safe.get)

        def didChange(params: LspHandler.DidChangeTextDocumentParams)(using Frame): Unit < (Async & Abort[Closed]) =
            Sync.Unsafe.defer(self.didChange(params).safe.get)

        def didSave(params: LspHandler.DidSaveTextDocumentParams)(using Frame): Unit < (Async & Abort[Closed]) =
            Sync.Unsafe.defer(self.didSave(params).safe.get)

        def didClose(params: LspHandler.DidCloseTextDocumentParams)(using Frame): Unit < (Async & Abort[Closed]) =
            Sync.Unsafe.defer(self.didClose(params).safe.get)

        def willSave(params: LspHandler.WillSaveTextDocumentParams)(using Frame): Unit < (Async & Abort[Closed]) =
            Sync.Unsafe.defer(self.willSave(params).safe.get)

        // workspace methods

        def workspaceSymbol(params: LspHandler.WorkspaceSymbolParams)(using
            Frame
        ): Chunk[LspHandler.WorkspaceSymbol] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.workspaceSymbol(params).safe.get)

        def executeCommand[T](params: LspHandler.ExecuteCommandParams)(using
            Frame,
            Schema[T]
        ): Maybe[T] < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.executeCommand[T](params).safe.get)

        def workspaceDiagnostic(params: LspHandler.WorkspaceDiagnosticParams)(using
            Frame
        ): LspHandler.WorkspaceDiagnosticReport < (Async & Abort[LspException | Closed]) =
            Sync.Unsafe.defer(self.workspaceDiagnostic(params).safe.get)

        // lifecycle methods

        def workDoneProgressCancel(params: LspHandler.WorkDoneProgressCancelParams)(using Frame): Unit < (Async & Abort[Closed]) =
            Sync.Unsafe.defer(self.workDoneProgressCancel(params).safe.get)

        def setTrace(params: LspHandler.SetTraceParams)(using Frame): Unit < (Async & Abort[Closed]) =
            Sync.Unsafe.defer(self.setTrace(params).safe.get)

        def cancel(id: JsonRpcId)(using Frame): Unit < (Async & Abort[Closed]) =
            Sync.Unsafe.defer(self.cancel(id).safe.get)

        // session properties

        def specVersion: String = self.specVersion

        def positionEncoding: LspHandler.PositionEncodingKind = self.positionEncoding

        def serverCapabilities: Maybe[LspCapabilities.Server.Server] = self.serverCapabilities

        def serverInfo: Maybe[LspInfo] = self.serverInfo

        def underlying: JsonRpcHandler = self.underlying

        def awaitDrain(using Frame): Unit < Async = Sync.Unsafe.defer(self.awaitDrain.safe.get)

        def close(using Frame): Unit < Async = Sync.Unsafe.defer(self.close(30.seconds).safe.get)

        def close(gracePeriod: Duration)(using Frame): Unit < Async = Sync.Unsafe.defer(self.close(gracePeriod).safe.get)

        def closeNow(using Frame): Unit < Async = Sync.Unsafe.defer(self.close(Duration.Zero).safe.get)

        def unsafe: Unsafe = self

    end extension

    // =========================================================================
    // Unsafe abstract class
    // =========================================================================

    /** The underlying unsafe implementation contract for the LSP client.
      *
      * All methods return `Fiber.Unsafe`. Bridge to safe tier through the extension methods
      * declared above. Concrete implementations are produced by the engine in Phase 07.
      */
    abstract class Unsafe:
        // textDocument requests
        def completion(params: LspHandler.CompletionParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Maybe[LspHandler.CompletionResult], Abort[LspException | Closed]]
        def completionItemResolve(item: LspHandler.CompletionItem)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[LspHandler.CompletionItem, Abort[LspException | Closed]]
        def hover(params: LspHandler.HoverParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Maybe[LspHandler.Hover], Abort[LspException | Closed]]
        def signatureHelp(params: LspHandler.SignatureHelpParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Maybe[LspHandler.SignatureHelp], Abort[LspException | Closed]]
        def declaration(params: LspHandler.DeclarationParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Maybe[LspHandler.DeclarationResult], Abort[LspException | Closed]]
        def definition(params: LspHandler.DefinitionParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Maybe[LspHandler.DefinitionResult], Abort[LspException | Closed]]
        def typeDefinition(params: LspHandler.TypeDefinitionParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Maybe[LspHandler.TypeDefinitionResult], Abort[LspException | Closed]]
        def implementation(params: LspHandler.ImplementationParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Maybe[LspHandler.ImplementationResult], Abort[LspException | Closed]]
        def references(params: LspHandler.ReferenceParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Chunk[LspHandler.Location], Abort[LspException | Closed]]
        def documentHighlight(params: LspHandler.DocumentHighlightParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Chunk[LspHandler.DocumentHighlight], Abort[LspException | Closed]]
        def documentSymbol(params: LspHandler.DocumentSymbolParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Maybe[LspHandler.DocumentSymbolResult], Abort[LspException | Closed]]
        def codeAction(params: LspHandler.CodeActionParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Chunk[LspHandler.CommandOrCodeAction], Abort[LspException | Closed]]
        def codeActionResolve(action: LspHandler.CodeAction)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[LspHandler.CodeAction, Abort[LspException | Closed]]
        def codeLens(params: LspHandler.CodeLensParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Chunk[LspHandler.CodeLens], Abort[LspException | Closed]]
        def codeLensResolve(lens: LspHandler.CodeLens)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[LspHandler.CodeLens, Abort[LspException | Closed]]
        def documentLink(params: LspHandler.DocumentLinkParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Chunk[LspHandler.DocumentLink], Abort[LspException | Closed]]
        def documentLinkResolve(link: LspHandler.DocumentLink)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[LspHandler.DocumentLink, Abort[LspException | Closed]]
        def documentColor(params: LspHandler.DocumentColorParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Chunk[LspHandler.ColorInformation], Abort[LspException | Closed]]
        def colorPresentation(params: LspHandler.ColorPresentationParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Chunk[LspHandler.ColorPresentation], Abort[LspException | Closed]]
        def formatting(params: LspHandler.DocumentFormattingParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Chunk[LspHandler.TextEdit], Abort[LspException | Closed]]
        def rangeFormatting(params: LspHandler.DocumentRangeFormattingParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Chunk[LspHandler.TextEdit], Abort[LspException | Closed]]
        def onTypeFormatting(params: LspHandler.DocumentOnTypeFormattingParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Chunk[LspHandler.TextEdit], Abort[LspException | Closed]]
        def rename(params: LspHandler.RenameParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Maybe[LspHandler.WorkspaceEdit], Abort[LspException | Closed]]
        def prepareRename(params: LspHandler.PrepareRenameParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Maybe[LspHandler.PrepareRenameResult], Abort[LspException | Closed]]
        def foldingRange(params: LspHandler.FoldingRangeParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Chunk[LspHandler.FoldingRange], Abort[LspException | Closed]]
        def selectionRange(params: LspHandler.SelectionRangeParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Chunk[LspHandler.SelectionRange], Abort[LspException | Closed]]
        def linkedEditingRange(params: LspHandler.LinkedEditingRangeParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Maybe[LspHandler.LinkedEditingRanges], Abort[LspException | Closed]]
        def prepareCallHierarchy(params: LspHandler.CallHierarchyPrepareParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Chunk[LspHandler.CallHierarchyItem], Abort[LspException | Closed]]
        def callHierarchyIncomingCalls(params: LspHandler.CallHierarchyIncomingCallsParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Chunk[LspHandler.CallHierarchyIncomingCall], Abort[LspException | Closed]]
        def callHierarchyOutgoingCalls(params: LspHandler.CallHierarchyOutgoingCallsParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Chunk[LspHandler.CallHierarchyOutgoingCall], Abort[LspException | Closed]]
        def prepareTypeHierarchy(params: LspHandler.TypeHierarchyPrepareParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Chunk[LspHandler.TypeHierarchyItem], Abort[LspException | Closed]]
        def typeHierarchySupertypes(params: LspHandler.TypeHierarchySupertypesParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Chunk[LspHandler.TypeHierarchyItem], Abort[LspException | Closed]]
        def typeHierarchySubtypes(params: LspHandler.TypeHierarchySubtypesParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Chunk[LspHandler.TypeHierarchyItem], Abort[LspException | Closed]]
        def semanticTokensFull(params: LspHandler.SemanticTokensParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Maybe[LspHandler.SemanticTokens], Abort[LspException | Closed]]
        def semanticTokensFullDelta(params: LspHandler.SemanticTokensDeltaParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Maybe[LspHandler.SemanticTokensResult], Abort[LspException | Closed]]
        def semanticTokensRange(params: LspHandler.SemanticTokensRangeParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Maybe[LspHandler.SemanticTokens], Abort[LspException | Closed]]
        def moniker(params: LspHandler.MonikerParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Chunk[LspHandler.Moniker], Abort[LspException | Closed]]
        def inlayHint(params: LspHandler.InlayHintParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Chunk[LspHandler.InlayHint], Abort[LspException | Closed]]
        def inlayHintResolve(hint: LspHandler.InlayHint)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[LspHandler.InlayHint, Abort[LspException | Closed]]
        def inlineValue(params: LspHandler.InlineValueParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Chunk[LspHandler.InlineValue], Abort[LspException | Closed]]
        def documentDiagnostic(params: LspHandler.DocumentDiagnosticParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[LspHandler.DocumentDiagnosticReport, Abort[LspException | Closed]]
        def willSaveWaitUntil(params: LspHandler.WillSaveTextDocumentParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Chunk[LspHandler.TextEdit], Abort[LspException | Closed]]
        // textDocument notifications
        def didOpen(params: LspHandler.DidOpenTextDocumentParams)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]]
        def didChange(params: LspHandler.DidChangeTextDocumentParams)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]]
        def didSave(params: LspHandler.DidSaveTextDocumentParams)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]]
        def didClose(params: LspHandler.DidCloseTextDocumentParams)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]]
        def willSave(params: LspHandler.WillSaveTextDocumentParams)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]]
        // workspace
        def workspaceSymbol(params: LspHandler.WorkspaceSymbolParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Chunk[LspHandler.WorkspaceSymbol], Abort[LspException | Closed]]
        def executeCommand[T](params: LspHandler.ExecuteCommandParams)(using
            AllowUnsafe,
            Frame,
            Schema[T]
        ): Fiber.Unsafe[Maybe[T], Abort[LspException | Closed]]
        def workspaceDiagnostic(params: LspHandler.WorkspaceDiagnosticParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[LspHandler.WorkspaceDiagnosticReport, Abort[LspException | Closed]]
        // lifecycle
        def workDoneProgressCancel(params: LspHandler.WorkDoneProgressCancelParams)(using
            AllowUnsafe,
            Frame
        ): Fiber.Unsafe[Unit, Abort[Closed]]
        def setTrace(params: LspHandler.SetTraceParams)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]]
        def cancel(id: JsonRpcId)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]]
        // session properties
        def specVersion: String
        def positionEncoding: LspHandler.PositionEncodingKind
        def serverCapabilities: Maybe[LspCapabilities.Server.Server]
        def serverInfo: Maybe[LspInfo]
        def underlying: JsonRpcHandler
        def awaitDrain(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any]
        def close(gracePeriod: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any]
        final def safe: LspClient = this
    end Unsafe

end LspClient
