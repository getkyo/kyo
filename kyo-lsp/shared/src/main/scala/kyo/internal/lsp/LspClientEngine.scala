package kyo.internal.lsp

import kyo.*

/** Composes all LSP engine components into a live `LspClient.Unsafe` instance.
  *
  * Client-side composition. Wiring order:
  *   1. Build `LspCatalog` from user handlers (throws synchronously for direction/duplicate errors).
  *   2. Create `LspDocumentRegistryImpl` with initial UTF-16 encoding.
  *   3. Install LSP policies on `config.jsonRpc` (cancellation, progress, unknownMethod).
  *   4. Lift user (client-direction) handlers via `LspHandlerLift.liftClient`.
  *   5. Call `JsonRpcHandler.initUnscoped` with all routes.
  *   6. Perform eager `initialize` / `initialized` handshake.
  *   7. Store negotiated server caps and encoding from `InitializeResult`.
  *   8. Return the concrete `LspClient.Unsafe` anonymous implementation.
  *
  * The argument order for all public variants is `(transport, clientInfo, capabilities, handlers)`.
  */
private[kyo] object LspClientEngine:

    // Wire shapes for the initialize exchange (client side).
    final private case class GeneralClientCapabilities(
        positionEncodings: Chunk[LspHandler.PositionEncodingKind] = Chunk.empty
    ) derives Schema

    final private case class InitializeClientParams(
        processId: Maybe[Int] = Absent,
        clientInfo: Maybe[LspInfo] = Absent,
        capabilities: LspCapabilities.Client.Client = LspCapabilities.Client.empty,
        locale: Maybe[String] = Absent
    ) derives Schema

    final private case class InitializeResult(
        capabilities: LspCapabilities.Server.Server,
        serverInfo: Maybe[LspInfo] = Absent
    ) derives Schema

    final private case class EmptyParams() derives Schema

    // Wire shapes for reverse-direction server notifications the client may receive.
    final private case class LogTraceParams(message: String, verbose: Maybe[String] = Absent) derives Schema
    final private case class ProgressParams(token: LspHandler.ProgressToken, value: LspHandler.WorkDoneProgressValue) derives Schema

    /** The session state refs an LSP client engine threads through its routes and handshake. The
      * forward ref (`clientRef`) is populated once the handler is constructed.
      */
    final private case class ClientRefs(
        negotiatedEncodingRef: AtomicRef[LspHandler.PositionEncodingKind],
        serverCapsRef: AtomicRef[Maybe[LspCapabilities.Server.Server]],
        serverInfoRef: AtomicRef[Maybe[LspInfo]],
        clientRef: AtomicRef[Maybe[LspClient.Unsafe]],
        registryImpl: LspDocumentRegistryImpl
    )

    private def initClientRefs(using Frame): ClientRefs < Sync =
        for
            negotiatedEncodingRef <- AtomicRef.init[LspHandler.PositionEncodingKind](LspHandler.PositionEncodingKind.UTF16)
            serverCapsRef         <- AtomicRef.init[Maybe[LspCapabilities.Server.Server]](Absent)
            serverInfoRef         <- AtomicRef.init[Maybe[LspInfo]](Absent)
            clientRef             <- AtomicRef.init[Maybe[LspClient.Unsafe]](Absent)
            registryImpl          <- LspDocumentRegistryImpl.init(negotiatedEncodingRef)
        yield ClientRefs(negotiatedEncodingRef, serverCapsRef, serverInfoRef, clientRef, registryImpl)

    def initClient(
        transport: JsonRpcTransport,
        clientInfo: LspInfo,
        capabilities: LspCapabilities.Client.Client,
        userHandlers: Seq[LspHandler[?, ?, ?]],
        config: LspConfig
    )(using Frame): LspClient.Unsafe < (Async & Abort[LspInitFailure]) =
        initClientRefs.flatMap { refs =>
            import refs.*

            // --- Catalog (throws synchronously on direction / duplicate / reserved errors) ---
            val catalog = LspCatalog.fromHandlers(userHandlers, LspHandler.Direction.ClientHandled)

            // registryImpl comes from initClientRefs

            // --- Config with policies installed ---
            val jsonRpcConfig = config.jsonRpc
                .cancellation(LspCancellationPolicy.default)
                .progress(LspProgressPolicy.default)
                .unknownMethod(JsonRpcUnknownMethodPolicy.minimal)

            // --- Lift user (client-direction) handlers ---
            val userRoutes: Seq[JsonRpcRoute[?, ?, ?]] = userHandlers
                .map(h => LspHandlerLift.liftClient(h, clientRef, registryImpl, negotiatedEncodingRef))

            JsonRpcHandler.initUnscoped(transport, userRoutes, jsonRpcConfig).map { handler =>

                // Perform the eager initialize / initialized handshake.
                // Build params with the client's position encoding preferences.
                val clientEncodings: Chunk[LspHandler.PositionEncodingKind] =
                    capabilities.general.fold(Chunk.empty[LspHandler.PositionEncodingKind])(_.positionEncodings)

                // Use server's preferred list from config as the advertised set if client has none.
                val advertisedEncodings: Chunk[LspHandler.PositionEncodingKind] =
                    if clientEncodings.isEmpty then config.positionEncodings else clientEncodings

                // Patch general capabilities to include the negotiated encoding list.
                val generalWithEncodings = LspCapabilities.Client.GeneralClientCapabilities(
                    positionEncodings = advertisedEncodings
                )
                val capsWithEncodings = capabilities.copy(general = Present(generalWithEncodings))

                val initParams = InitializeClientParams(
                    processId = Absent,
                    clientInfo = Present(clientInfo),
                    capabilities = capsWithEncodings
                )

                // Send initialize request and receive InitializeResult.
                val handshakeEffect: Unit < (Async & Abort[LspInitFailure | Closed]) =
                    handler.call[InitializeClientParams, InitializeResult]("initialize", initParams)
                        .handle(Abort.recover[JsonRpcError] { e =>
                            Abort.fail(LspRemoteException(e.code, e.message, e.data))
                        })
                        .flatMap { result =>
                            // Negotiate position encoding: first match of server's list against our preferences.
                            val serverEncoding = result.capabilities.positionEncoding.getOrElse(LspHandler.PositionEncodingKind.UTF16)
                            val negotiated =
                                if advertisedEncodings.contains(serverEncoding) then serverEncoding
                                else advertisedEncodings.headOption.getOrElse(LspHandler.PositionEncodingKind.UTF16)
                            // Store server capabilities, server info, and the negotiated encoding.
                            serverCapsRef.set(Present(result.capabilities))
                                .andThen(serverInfoRef.set(result.serverInfo))
                                .andThen(negotiatedEncodingRef.set(negotiated))
                        }
                        .andThen(handler.notify[EmptyParams]("initialized", EmptyParams()))

                // Run the handshake. On failure, abort the client construction with the error.
                val handshakeRun: Unit < (Async & Abort[LspInitFailure]) =
                    handshakeEffect
                        .handle(Abort.recover[Closed] { _ =>
                            Abort.fail(LspConnectionClosedException())
                        })

                handshakeRun.andThen {
                    val unsafe: LspClient.Unsafe = new LspClient.Unsafe:

                        private def notifyEffect[In: Schema](method: String, params: In)(using
                            Frame
                        ): Unit < (Async & Abort[LspConnectionClosedException]) =
                            handler.notify[In](method, params)
                                .handle(Abort.recover[Closed] { _ => Abort.fail(LspConnectionClosedException()) })

                        private def callEffect[In: Schema, Out: Schema](method: String, params: In)(using
                            Frame
                        ): Out < (Async & Abort[LspRequestFailure]) =
                            handler.call[In, Out](method, params)
                                .handle(Abort.recover[JsonRpcError] { e =>
                                    Abort.fail(LspRemoteException(e.code, e.message, e.data))
                                })
                                .handle(Abort.recover[Closed] { _ =>
                                    Abort.fail(LspConnectionClosedException())
                                })

                        // textDocument requests

                        def completion(params: LspHandler.CompletionParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Maybe[LspHandler.CompletionResult], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.CompletionParams, Maybe[LspHandler.CompletionResult]](
                                "textDocument/completion",
                                params
                            ))

                        def completionItemResolve(item: LspHandler.CompletionItem)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[LspHandler.CompletionItem, Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.CompletionItem, LspHandler.CompletionItem](
                                "completionItem/resolve",
                                item
                            ))

                        def hover(params: LspHandler.HoverParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Maybe[LspHandler.Hover], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.HoverParams, Maybe[LspHandler.Hover]](
                                "textDocument/hover",
                                params
                            ))

                        def signatureHelp(params: LspHandler.SignatureHelpParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Maybe[LspHandler.SignatureHelp], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.SignatureHelpParams, Maybe[LspHandler.SignatureHelp]](
                                "textDocument/signatureHelp",
                                params
                            ))

                        def declaration(params: LspHandler.DeclarationParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Maybe[LspHandler.DeclarationResult], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.DeclarationParams, Maybe[LspHandler.DeclarationResult]](
                                "textDocument/declaration",
                                params
                            ))

                        def definition(params: LspHandler.DefinitionParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Maybe[LspHandler.DefinitionResult], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.DefinitionParams, Maybe[LspHandler.DefinitionResult]](
                                "textDocument/definition",
                                params
                            ))

                        def typeDefinition(params: LspHandler.TypeDefinitionParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Maybe[LspHandler.TypeDefinitionResult], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.TypeDefinitionParams, Maybe[LspHandler.TypeDefinitionResult]](
                                "textDocument/typeDefinition",
                                params
                            ))

                        def implementation(params: LspHandler.ImplementationParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Maybe[LspHandler.ImplementationResult], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.ImplementationParams, Maybe[LspHandler.ImplementationResult]](
                                "textDocument/implementation",
                                params
                            ))

                        def references(params: LspHandler.ReferenceParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Chunk[LspHandler.Location], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.ReferenceParams, Chunk[LspHandler.Location]](
                                "textDocument/references",
                                params
                            ))

                        def documentHighlight(params: LspHandler.DocumentHighlightParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Chunk[LspHandler.DocumentHighlight], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.DocumentHighlightParams, Chunk[LspHandler.DocumentHighlight]](
                                "textDocument/documentHighlight",
                                params
                            ))

                        def documentSymbol(params: LspHandler.DocumentSymbolParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Maybe[LspHandler.DocumentSymbolResult], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.DocumentSymbolParams, Maybe[LspHandler.DocumentSymbolResult]](
                                "textDocument/documentSymbol",
                                params
                            ))

                        def codeAction(params: LspHandler.CodeActionParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Chunk[LspHandler.CommandOrCodeAction], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.CodeActionParams, Chunk[LspHandler.CommandOrCodeAction]](
                                "textDocument/codeAction",
                                params
                            ))

                        def codeActionResolve(action: LspHandler.CodeAction)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[LspHandler.CodeAction, Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.CodeAction, LspHandler.CodeAction](
                                "codeAction/resolve",
                                action
                            ))

                        def codeLens(params: LspHandler.CodeLensParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Chunk[LspHandler.CodeLens], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.CodeLensParams, Chunk[LspHandler.CodeLens]](
                                "textDocument/codeLens",
                                params
                            ))

                        def codeLensResolve(lens: LspHandler.CodeLens)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[LspHandler.CodeLens, Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.CodeLens, LspHandler.CodeLens](
                                "codeLens/resolve",
                                lens
                            ))

                        def documentLink(params: LspHandler.DocumentLinkParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Chunk[LspHandler.DocumentLink], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.DocumentLinkParams, Chunk[LspHandler.DocumentLink]](
                                "textDocument/documentLink",
                                params
                            ))

                        def documentLinkResolve(link: LspHandler.DocumentLink)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[LspHandler.DocumentLink, Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.DocumentLink, LspHandler.DocumentLink](
                                "documentLink/resolve",
                                link
                            ))

                        def documentColor(params: LspHandler.DocumentColorParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Chunk[LspHandler.ColorInformation], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.DocumentColorParams, Chunk[LspHandler.ColorInformation]](
                                "textDocument/documentColor",
                                params
                            ))

                        def colorPresentation(params: LspHandler.ColorPresentationParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Chunk[LspHandler.ColorPresentation], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.ColorPresentationParams, Chunk[LspHandler.ColorPresentation]](
                                "textDocument/colorPresentation",
                                params
                            ))

                        def formatting(params: LspHandler.DocumentFormattingParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Chunk[LspHandler.TextEdit], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.DocumentFormattingParams, Chunk[LspHandler.TextEdit]](
                                "textDocument/formatting",
                                params
                            ))

                        def rangeFormatting(params: LspHandler.DocumentRangeFormattingParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Chunk[LspHandler.TextEdit], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.DocumentRangeFormattingParams, Chunk[LspHandler.TextEdit]](
                                "textDocument/rangeFormatting",
                                params
                            ))

                        def onTypeFormatting(params: LspHandler.DocumentOnTypeFormattingParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Chunk[LspHandler.TextEdit], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.DocumentOnTypeFormattingParams, Chunk[LspHandler.TextEdit]](
                                "textDocument/onTypeFormatting",
                                params
                            ))

                        def rename(params: LspHandler.RenameParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Maybe[LspHandler.WorkspaceEdit], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.RenameParams, Maybe[LspHandler.WorkspaceEdit]](
                                "textDocument/rename",
                                params
                            ))

                        def prepareRename(params: LspHandler.PrepareRenameParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Maybe[LspHandler.PrepareRenameResult], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.PrepareRenameParams, Maybe[LspHandler.PrepareRenameResult]](
                                "textDocument/prepareRename",
                                params
                            ))

                        def foldingRange(params: LspHandler.FoldingRangeParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Chunk[LspHandler.FoldingRange], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.FoldingRangeParams, Chunk[LspHandler.FoldingRange]](
                                "textDocument/foldingRange",
                                params
                            ))

                        def selectionRange(params: LspHandler.SelectionRangeParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Chunk[LspHandler.SelectionRange], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.SelectionRangeParams, Chunk[LspHandler.SelectionRange]](
                                "textDocument/selectionRange",
                                params
                            ))

                        def linkedEditingRange(params: LspHandler.LinkedEditingRangeParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Maybe[LspHandler.LinkedEditingRanges], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.LinkedEditingRangeParams, Maybe[LspHandler.LinkedEditingRanges]](
                                "textDocument/linkedEditingRange",
                                params
                            ))

                        def prepareCallHierarchy(params: LspHandler.CallHierarchyPrepareParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Chunk[LspHandler.CallHierarchyItem], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.CallHierarchyPrepareParams, Chunk[LspHandler.CallHierarchyItem]](
                                "textDocument/prepareCallHierarchy",
                                params
                            ))

                        def callHierarchyIncomingCalls(params: LspHandler.CallHierarchyIncomingCallsParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Chunk[LspHandler.CallHierarchyIncomingCall], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(
                                callEffect[LspHandler.CallHierarchyIncomingCallsParams, Chunk[LspHandler.CallHierarchyIncomingCall]](
                                    "callHierarchy/incomingCalls",
                                    params
                                )
                            )

                        def callHierarchyOutgoingCalls(params: LspHandler.CallHierarchyOutgoingCallsParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Chunk[LspHandler.CallHierarchyOutgoingCall], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(
                                callEffect[LspHandler.CallHierarchyOutgoingCallsParams, Chunk[LspHandler.CallHierarchyOutgoingCall]](
                                    "callHierarchy/outgoingCalls",
                                    params
                                )
                            )

                        def prepareTypeHierarchy(params: LspHandler.TypeHierarchyPrepareParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Chunk[LspHandler.TypeHierarchyItem], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.TypeHierarchyPrepareParams, Chunk[LspHandler.TypeHierarchyItem]](
                                "textDocument/prepareTypeHierarchy",
                                params
                            ))

                        def typeHierarchySupertypes(params: LspHandler.TypeHierarchySupertypesParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Chunk[LspHandler.TypeHierarchyItem], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.TypeHierarchySupertypesParams, Chunk[LspHandler.TypeHierarchyItem]](
                                "typeHierarchy/supertypes",
                                params
                            ))

                        def typeHierarchySubtypes(params: LspHandler.TypeHierarchySubtypesParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Chunk[LspHandler.TypeHierarchyItem], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.TypeHierarchySubtypesParams, Chunk[LspHandler.TypeHierarchyItem]](
                                "typeHierarchy/subtypes",
                                params
                            ))

                        def semanticTokensFull(params: LspHandler.SemanticTokensParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Maybe[LspHandler.SemanticTokens], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.SemanticTokensParams, Maybe[LspHandler.SemanticTokens]](
                                "textDocument/semanticTokens/full",
                                params
                            ))

                        def semanticTokensFullDelta(params: LspHandler.SemanticTokensDeltaParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Maybe[LspHandler.SemanticTokensResult], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.SemanticTokensDeltaParams, Maybe[LspHandler.SemanticTokensResult]](
                                "textDocument/semanticTokens/full/delta",
                                params
                            ))

                        def semanticTokensRange(params: LspHandler.SemanticTokensRangeParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Maybe[LspHandler.SemanticTokens], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.SemanticTokensRangeParams, Maybe[LspHandler.SemanticTokens]](
                                "textDocument/semanticTokens/range",
                                params
                            ))

                        def moniker(params: LspHandler.MonikerParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Chunk[LspHandler.Moniker], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.MonikerParams, Chunk[LspHandler.Moniker]](
                                "textDocument/moniker",
                                params
                            ))

                        def inlayHint(params: LspHandler.InlayHintParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Chunk[LspHandler.InlayHint], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.InlayHintParams, Chunk[LspHandler.InlayHint]](
                                "textDocument/inlayHint",
                                params
                            ))

                        def inlayHintResolve(hint: LspHandler.InlayHint)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[LspHandler.InlayHint, Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.InlayHint, LspHandler.InlayHint](
                                "inlayHint/resolve",
                                hint
                            ))

                        def inlineValue(params: LspHandler.InlineValueParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Chunk[LspHandler.InlineValue], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.InlineValueParams, Chunk[LspHandler.InlineValue]](
                                "textDocument/inlineValue",
                                params
                            ))

                        def documentDiagnostic(params: LspHandler.DocumentDiagnosticParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[LspHandler.DocumentDiagnosticReport, Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.DocumentDiagnosticParams, LspHandler.DocumentDiagnosticReport](
                                "textDocument/diagnostic",
                                params
                            ))

                        def willSaveWaitUntil(params: LspHandler.WillSaveTextDocumentParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Chunk[LspHandler.TextEdit], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.WillSaveTextDocumentParams, Chunk[LspHandler.TextEdit]](
                                "textDocument/willSaveWaitUntil",
                                params
                            ))

                        // textDocument notifications

                        def didOpen(params: LspHandler.DidOpenTextDocumentParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Unit, Abort[LspConnectionClosedException]] =
                            Fiber.Unsafe.init(notifyEffect("textDocument/didOpen", params))

                        def didChange(params: LspHandler.DidChangeTextDocumentParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Unit, Abort[LspConnectionClosedException]] =
                            Fiber.Unsafe.init(notifyEffect("textDocument/didChange", params))

                        def didSave(params: LspHandler.DidSaveTextDocumentParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Unit, Abort[LspConnectionClosedException]] =
                            Fiber.Unsafe.init(notifyEffect("textDocument/didSave", params))

                        def didClose(params: LspHandler.DidCloseTextDocumentParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Unit, Abort[LspConnectionClosedException]] =
                            Fiber.Unsafe.init(notifyEffect("textDocument/didClose", params))

                        def willSave(params: LspHandler.WillSaveTextDocumentParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Unit, Abort[LspConnectionClosedException]] =
                            Fiber.Unsafe.init(notifyEffect("textDocument/willSave", params))

                        // workspace

                        def workspaceSymbol(params: LspHandler.WorkspaceSymbolParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Chunk[LspHandler.WorkspaceSymbol], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.WorkspaceSymbolParams, Chunk[LspHandler.WorkspaceSymbol]](
                                "workspace/symbol",
                                params
                            ))

                        def executeCommand[T](params: LspHandler.ExecuteCommandParams)(using
                            AllowUnsafe,
                            Frame,
                            Schema[T]
                        ): Fiber.Unsafe[Maybe[T], Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.ExecuteCommandParams, Maybe[T]](
                                "workspace/executeCommand",
                                params
                            ))

                        def workspaceDiagnostic(params: LspHandler.WorkspaceDiagnosticParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[LspHandler.WorkspaceDiagnosticReport, Abort[LspRequestFailure]] =
                            Fiber.Unsafe.init(callEffect[LspHandler.WorkspaceDiagnosticParams, LspHandler.WorkspaceDiagnosticReport](
                                "workspace/diagnostic",
                                params
                            ))

                        // lifecycle

                        def workDoneProgressCancel(params: LspHandler.WorkDoneProgressCancelParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Unit, Abort[LspConnectionClosedException]] =
                            Fiber.Unsafe.init(notifyEffect("window/workDoneProgress/cancel", params))

                        def setTrace(params: LspHandler.SetTraceParams)(using
                            AllowUnsafe,
                            Frame
                        ): Fiber.Unsafe[Unit, Abort[LspConnectionClosedException]] =
                            Fiber.Unsafe.init(notifyEffect("$/setTrace", params))

                        def cancel(id: JsonRpcId)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[LspConnectionClosedException]] =
                            Fiber.Unsafe.init(
                                handler.cancel(id).handle(Abort.recover[Closed] { _ => Abort.fail(LspConnectionClosedException()) })
                            )

                        // session properties

                        def specVersion: String = LspConfig.SpecVersion

                        def positionEncoding(using Frame): LspHandler.PositionEncodingKind < Sync =
                            negotiatedEncodingRef.get

                        def serverCapabilities(using Frame): Maybe[LspCapabilities.Server.Server] < Sync =
                            serverCapsRef.get

                        def serverInfo(using Frame): Maybe[LspInfo] < Sync =
                            serverInfoRef.get

                        def underlying: JsonRpcHandler = handler

                        def awaitDrain(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
                            Fiber.Unsafe.init(handler.awaitDrain)

                        def close(gracePeriod: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
                            Fiber.Unsafe.init(handler.close(gracePeriod))

                    end unsafe

                    // Populate clientRef so lifted client handlers can bind it into Lsp.local.
                    clientRef.set(Present(unsafe)).andThen(unsafe)
                }
            }
        }
    end initClient

end LspClientEngine
