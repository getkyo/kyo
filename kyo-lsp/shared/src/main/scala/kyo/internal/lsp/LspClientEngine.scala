package kyo.internal.lsp

import kyo.*

/** Composes all LSP engine components into a live `LspClient.Unsafe` instance.
  *
  * Mirrors `LspEngine.initServer` but for the client side. Wiring order:
  *   1. Build `LspCatalog` from user handlers (throws synchronously for direction/duplicate errors).
  *   2. Create `LspDocumentRegistryImpl` with initial UTF-16 encoding.
  *   3. Install LSP policies on `config.jsonRpc` (cancellation, progress, unknownMethod).
  *   4. Lift user (client-direction) handlers via `LspHandlerLift.liftClient`.
  *   5. Call `JsonRpcHandler.initUnscoped` with all routes.
  *   6. Perform eager `initialize` / `initialized` handshake per INV-061.
  *   7. Store negotiated server caps and encoding from `InitializeResult`.
  *   8. Return the concrete `LspClient.Unsafe` anonymous implementation.
  *
  * Per INV-060 the argument order for all public variants is
  * `(transport, clientInfo, capabilities, handlers)`.
  *
  * Per INV-030, INV-031, INV-061.
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

    def initClient(
        transport: JsonRpcTransport,
        clientInfo: LspInfo,
        capabilities: LspCapabilities.Client.Client,
        userHandlers: Seq[LspHandler[?, ?, ?]],
        config: LspConfig
    )(using Frame): LspClient.Unsafe < (Async & Abort[LspException]) =

        // --- State refs ---
        val negotiatedEncodingRef = AtomicRef.Unsafe.init[LspHandler.PositionEncodingKind](
            LspHandler.PositionEncodingKind.UTF16
        )(using AllowUnsafe.embrace.danger).safe
        val serverCapsRef = AtomicRef.Unsafe.init[Maybe[LspCapabilities.Server.Server]](Absent)(
            using AllowUnsafe.embrace.danger
        ).safe
        val serverInfoRef = AtomicRef.Unsafe.init[Maybe[LspInfo]](Absent)(using AllowUnsafe.embrace.danger).safe
        val clientRef     = AtomicRef.Unsafe.init[Maybe[LspClient.Unsafe]](Absent)(using AllowUnsafe.embrace.danger).safe

        // --- Catalog (throws synchronously on direction / duplicate / reserved errors) ---
        val catalog = LspCatalog.fromHandlers(userHandlers, LspHandler.Direction.ClientHandled)

        // --- Document registry ---
        val registryImpl = new LspDocumentRegistryImpl(negotiatedEncodingRef)

        // --- Config with policies installed ---
        val jsonRpcConfig = config.jsonRpc
            .cancellation(LspCancellationPolicy.default)
            .progress(LspProgressPolicy.default)
            .unknownMethod(JsonRpcUnknownMethodPolicy.minimal)

        // --- Lift user (client-direction) handlers ---
        val userRoutes: Seq[JsonRpcRoute[?, ?, ?]] = userHandlers
            .map(h => LspHandlerLift.liftClient(h, clientRef, registryImpl, negotiatedEncodingRef))

        JsonRpcHandler.initUnscoped(transport, userRoutes, jsonRpcConfig).map { handler =>

            // Perform the eager initialize / initialized handshake (INV-061).
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
            val handshakeEffect: Unit < (Async & Abort[LspException | Closed]) =
                handler.call[InitializeClientParams, InitializeResult]("initialize", initParams)
                    .handle(Abort.recover[JsonRpcError] { e =>
                        Abort.fail(LspException.Application.Remote(e.code, e.message, e.data))
                    })
                    .map { result =>
                        // Store server capabilities.
                        serverCapsRef.unsafe.set(Present(result.capabilities))(using AllowUnsafe.embrace.danger)
                        serverInfoRef.unsafe.set(result.serverInfo)(using AllowUnsafe.embrace.danger)

                        // Negotiate position encoding: first match of server's list against our preferences.
                        val serverEncoding = result.capabilities.positionEncoding.getOrElse(LspHandler.PositionEncodingKind.UTF16)
                        val negotiated =
                            if advertisedEncodings.contains(serverEncoding) then serverEncoding
                            else advertisedEncodings.headOption.getOrElse(LspHandler.PositionEncodingKind.UTF16)
                        negotiatedEncodingRef.unsafe.set(negotiated)(using AllowUnsafe.embrace.danger)

                        // Send initialized notification to complete the handshake.
                        ()
                    }
                    .andThen(handler.notify[EmptyParams]("initialized", EmptyParams()))

            // Run the handshake. On failure, abort the client construction with the error (INV-061).
            val handshakeRun: Unit < (Async & Abort[LspException]) =
                handshakeEffect
                    .handle(Abort.recover[Closed] { _ =>
                        Abort.fail(LspException.Handshake.NotInitialized("initialize"))
                    })

            handshakeRun.andThen {
                val unsafe: LspClient.Unsafe = new LspClient.Unsafe:

                    private def notifyEffect[In: Schema](method: String, params: In)(using Frame): Unit < (Async & Abort[Closed]) =
                        handler.notify[In](method, params)

                    private def callEffect[In: Schema, Out: Schema](method: String, params: In)(using
                        Frame
                    ): Out < (Async & Abort[LspException | Closed]) =
                        handler.call[In, Out](method, params)
                            .handle(Abort.recover[JsonRpcError] { e =>
                                Abort.fail(LspException.Application.Remote(e.code, e.message, e.data))
                            })

                    // textDocument requests

                    def completion(params: LspHandler.CompletionParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Maybe[LspHandler.CompletionResult], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.CompletionParams, Maybe[LspHandler.CompletionResult]](
                                "textDocument/completion",
                                params
                            ))
                        ).unsafe

                    def completionItemResolve(item: LspHandler.CompletionItem)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[LspHandler.CompletionItem, Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.CompletionItem, LspHandler.CompletionItem](
                                "completionItem/resolve",
                                item
                            ))
                        ).unsafe

                    def hover(params: LspHandler.HoverParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Maybe[LspHandler.Hover], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.HoverParams, Maybe[LspHandler.Hover]](
                                "textDocument/hover",
                                params
                            ))
                        ).unsafe

                    def signatureHelp(params: LspHandler.SignatureHelpParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Maybe[LspHandler.SignatureHelp], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.SignatureHelpParams, Maybe[LspHandler.SignatureHelp]](
                                "textDocument/signatureHelp",
                                params
                            ))
                        ).unsafe

                    def declaration(params: LspHandler.DeclarationParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Maybe[LspHandler.DeclarationResult], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.DeclarationParams, Maybe[LspHandler.DeclarationResult]](
                                "textDocument/declaration",
                                params
                            ))
                        ).unsafe

                    def definition(params: LspHandler.DefinitionParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Maybe[LspHandler.DefinitionResult], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.DefinitionParams, Maybe[LspHandler.DefinitionResult]](
                                "textDocument/definition",
                                params
                            ))
                        ).unsafe

                    def typeDefinition(params: LspHandler.TypeDefinitionParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Maybe[LspHandler.TypeDefinitionResult], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.TypeDefinitionParams, Maybe[LspHandler.TypeDefinitionResult]](
                                "textDocument/typeDefinition",
                                params
                            ))
                        ).unsafe

                    def implementation(params: LspHandler.ImplementationParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Maybe[LspHandler.ImplementationResult], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.ImplementationParams, Maybe[LspHandler.ImplementationResult]](
                                "textDocument/implementation",
                                params
                            ))
                        ).unsafe

                    def references(params: LspHandler.ReferenceParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Chunk[LspHandler.Location], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.ReferenceParams, Chunk[LspHandler.Location]](
                                "textDocument/references",
                                params
                            ))
                        ).unsafe

                    def documentHighlight(params: LspHandler.DocumentHighlightParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Chunk[LspHandler.DocumentHighlight], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.DocumentHighlightParams, Chunk[LspHandler.DocumentHighlight]](
                                "textDocument/documentHighlight",
                                params
                            ))
                        ).unsafe

                    def documentSymbol(params: LspHandler.DocumentSymbolParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Maybe[LspHandler.DocumentSymbolResult], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.DocumentSymbolParams, Maybe[LspHandler.DocumentSymbolResult]](
                                "textDocument/documentSymbol",
                                params
                            ))
                        ).unsafe

                    def codeAction(params: LspHandler.CodeActionParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Chunk[LspHandler.CommandOrCodeAction], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.CodeActionParams, Chunk[LspHandler.CommandOrCodeAction]](
                                "textDocument/codeAction",
                                params
                            ))
                        ).unsafe

                    def codeActionResolve(action: LspHandler.CodeAction)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[LspHandler.CodeAction, Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.CodeAction, LspHandler.CodeAction](
                                "codeAction/resolve",
                                action
                            ))
                        ).unsafe

                    def codeLens(params: LspHandler.CodeLensParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Chunk[LspHandler.CodeLens], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.CodeLensParams, Chunk[LspHandler.CodeLens]](
                                "textDocument/codeLens",
                                params
                            ))
                        ).unsafe

                    def codeLensResolve(lens: LspHandler.CodeLens)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[LspHandler.CodeLens, Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.CodeLens, LspHandler.CodeLens](
                                "codeLens/resolve",
                                lens
                            ))
                        ).unsafe

                    def documentLink(params: LspHandler.DocumentLinkParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Chunk[LspHandler.DocumentLink], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.DocumentLinkParams, Chunk[LspHandler.DocumentLink]](
                                "textDocument/documentLink",
                                params
                            ))
                        ).unsafe

                    def documentLinkResolve(link: LspHandler.DocumentLink)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[LspHandler.DocumentLink, Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.DocumentLink, LspHandler.DocumentLink](
                                "documentLink/resolve",
                                link
                            ))
                        ).unsafe

                    def documentColor(params: LspHandler.DocumentColorParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Chunk[LspHandler.ColorInformation], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.DocumentColorParams, Chunk[LspHandler.ColorInformation]](
                                "textDocument/documentColor",
                                params
                            ))
                        ).unsafe

                    def colorPresentation(params: LspHandler.ColorPresentationParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Chunk[LspHandler.ColorPresentation], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.ColorPresentationParams, Chunk[LspHandler.ColorPresentation]](
                                "textDocument/colorPresentation",
                                params
                            ))
                        ).unsafe

                    def formatting(params: LspHandler.DocumentFormattingParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Chunk[LspHandler.TextEdit], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.DocumentFormattingParams, Chunk[LspHandler.TextEdit]](
                                "textDocument/formatting",
                                params
                            ))
                        ).unsafe

                    def rangeFormatting(params: LspHandler.DocumentRangeFormattingParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Chunk[LspHandler.TextEdit], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.DocumentRangeFormattingParams, Chunk[LspHandler.TextEdit]](
                                "textDocument/rangeFormatting",
                                params
                            ))
                        ).unsafe

                    def onTypeFormatting(params: LspHandler.DocumentOnTypeFormattingParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Chunk[LspHandler.TextEdit], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.DocumentOnTypeFormattingParams, Chunk[LspHandler.TextEdit]](
                                "textDocument/onTypeFormatting",
                                params
                            ))
                        ).unsafe

                    def rename(params: LspHandler.RenameParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Maybe[LspHandler.WorkspaceEdit], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.RenameParams, Maybe[LspHandler.WorkspaceEdit]](
                                "textDocument/rename",
                                params
                            ))
                        ).unsafe

                    def prepareRename(params: LspHandler.PrepareRenameParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Maybe[LspHandler.PrepareRenameResult], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.PrepareRenameParams, Maybe[LspHandler.PrepareRenameResult]](
                                "textDocument/prepareRename",
                                params
                            ))
                        ).unsafe

                    def foldingRange(params: LspHandler.FoldingRangeParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Chunk[LspHandler.FoldingRange], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.FoldingRangeParams, Chunk[LspHandler.FoldingRange]](
                                "textDocument/foldingRange",
                                params
                            ))
                        ).unsafe

                    def selectionRange(params: LspHandler.SelectionRangeParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Chunk[LspHandler.SelectionRange], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.SelectionRangeParams, Chunk[LspHandler.SelectionRange]](
                                "textDocument/selectionRange",
                                params
                            ))
                        ).unsafe

                    def linkedEditingRange(params: LspHandler.LinkedEditingRangeParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Maybe[LspHandler.LinkedEditingRanges], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.LinkedEditingRangeParams, Maybe[LspHandler.LinkedEditingRanges]](
                                "textDocument/linkedEditingRange",
                                params
                            ))
                        ).unsafe

                    def prepareCallHierarchy(params: LspHandler.CallHierarchyPrepareParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Chunk[LspHandler.CallHierarchyItem], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.CallHierarchyPrepareParams, Chunk[LspHandler.CallHierarchyItem]](
                                "textDocument/prepareCallHierarchy",
                                params
                            ))
                        ).unsafe

                    def callHierarchyIncomingCalls(params: LspHandler.CallHierarchyIncomingCallsParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Chunk[LspHandler.CallHierarchyIncomingCall], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(
                                callEffect[LspHandler.CallHierarchyIncomingCallsParams, Chunk[LspHandler.CallHierarchyIncomingCall]](
                                    "callHierarchy/incomingCalls",
                                    params
                                )
                            )
                        ).unsafe

                    def callHierarchyOutgoingCalls(params: LspHandler.CallHierarchyOutgoingCallsParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Chunk[LspHandler.CallHierarchyOutgoingCall], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(
                                callEffect[LspHandler.CallHierarchyOutgoingCallsParams, Chunk[LspHandler.CallHierarchyOutgoingCall]](
                                    "callHierarchy/outgoingCalls",
                                    params
                                )
                            )
                        ).unsafe

                    def prepareTypeHierarchy(params: LspHandler.TypeHierarchyPrepareParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Chunk[LspHandler.TypeHierarchyItem], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.TypeHierarchyPrepareParams, Chunk[LspHandler.TypeHierarchyItem]](
                                "textDocument/prepareTypeHierarchy",
                                params
                            ))
                        ).unsafe

                    def typeHierarchySupertypes(params: LspHandler.TypeHierarchySupertypesParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Chunk[LspHandler.TypeHierarchyItem], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.TypeHierarchySupertypesParams, Chunk[LspHandler.TypeHierarchyItem]](
                                "typeHierarchy/supertypes",
                                params
                            ))
                        ).unsafe

                    def typeHierarchySubtypes(params: LspHandler.TypeHierarchySubtypesParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Chunk[LspHandler.TypeHierarchyItem], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.TypeHierarchySubtypesParams, Chunk[LspHandler.TypeHierarchyItem]](
                                "typeHierarchy/subtypes",
                                params
                            ))
                        ).unsafe

                    def semanticTokensFull(params: LspHandler.SemanticTokensParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Maybe[LspHandler.SemanticTokens], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.SemanticTokensParams, Maybe[LspHandler.SemanticTokens]](
                                "textDocument/semanticTokens/full",
                                params
                            ))
                        ).unsafe

                    def semanticTokensFullDelta(params: LspHandler.SemanticTokensDeltaParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Maybe[LspHandler.SemanticTokensResult], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.SemanticTokensDeltaParams, Maybe[LspHandler.SemanticTokensResult]](
                                "textDocument/semanticTokens/full/delta",
                                params
                            ))
                        ).unsafe

                    def semanticTokensRange(params: LspHandler.SemanticTokensRangeParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Maybe[LspHandler.SemanticTokens], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.SemanticTokensRangeParams, Maybe[LspHandler.SemanticTokens]](
                                "textDocument/semanticTokens/range",
                                params
                            ))
                        ).unsafe

                    def moniker(params: LspHandler.MonikerParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Chunk[LspHandler.Moniker], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.MonikerParams, Chunk[LspHandler.Moniker]](
                                "textDocument/moniker",
                                params
                            ))
                        ).unsafe

                    def inlayHint(params: LspHandler.InlayHintParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Chunk[LspHandler.InlayHint], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.InlayHintParams, Chunk[LspHandler.InlayHint]](
                                "textDocument/inlayHint",
                                params
                            ))
                        ).unsafe

                    def inlayHintResolve(hint: LspHandler.InlayHint)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[LspHandler.InlayHint, Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.InlayHint, LspHandler.InlayHint](
                                "inlayHint/resolve",
                                hint
                            ))
                        ).unsafe

                    def inlineValue(params: LspHandler.InlineValueParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Chunk[LspHandler.InlineValue], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.InlineValueParams, Chunk[LspHandler.InlineValue]](
                                "textDocument/inlineValue",
                                params
                            ))
                        ).unsafe

                    def documentDiagnostic(params: LspHandler.DocumentDiagnosticParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[LspHandler.DocumentDiagnosticReport, Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.DocumentDiagnosticParams, LspHandler.DocumentDiagnosticReport](
                                "textDocument/diagnostic",
                                params
                            ))
                        ).unsafe

                    def willSaveWaitUntil(params: LspHandler.WillSaveTextDocumentParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Chunk[LspHandler.TextEdit], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.WillSaveTextDocumentParams, Chunk[LspHandler.TextEdit]](
                                "textDocument/willSaveWaitUntil",
                                params
                            ))
                        ).unsafe

                    // textDocument notifications

                    def didOpen(params: LspHandler.DidOpenTextDocumentParams)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(notifyEffect("textDocument/didOpen", params))
                        ).unsafe

                    def didChange(params: LspHandler.DidChangeTextDocumentParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Unit, Abort[Closed]] =
                        Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(notifyEffect("textDocument/didChange", params))).unsafe

                    def didSave(params: LspHandler.DidSaveTextDocumentParams)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]] =
                        Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(notifyEffect("textDocument/didSave", params))).unsafe

                    def didClose(params: LspHandler.DidCloseTextDocumentParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Unit, Abort[Closed]] =
                        Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(notifyEffect("textDocument/didClose", params))).unsafe

                    def willSave(params: LspHandler.WillSaveTextDocumentParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Unit, Abort[Closed]] =
                        Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(notifyEffect("textDocument/willSave", params))).unsafe

                    // workspace

                    def workspaceSymbol(params: LspHandler.WorkspaceSymbolParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Chunk[LspHandler.WorkspaceSymbol], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.WorkspaceSymbolParams, Chunk[LspHandler.WorkspaceSymbol]](
                                "workspace/symbol",
                                params
                            ))
                        ).unsafe

                    def executeCommand[T](params: LspHandler.ExecuteCommandParams)(using
                        AllowUnsafe,
                        Frame,
                        Schema[T]
                    ): Fiber.Unsafe[Maybe[T], Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.ExecuteCommandParams, Maybe[T]](
                                "workspace/executeCommand",
                                params
                            ))
                        ).unsafe

                    def workspaceDiagnostic(params: LspHandler.WorkspaceDiagnosticParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[LspHandler.WorkspaceDiagnosticReport, Abort[LspException | Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(callEffect[LspHandler.WorkspaceDiagnosticParams, LspHandler.WorkspaceDiagnosticReport](
                                "workspace/diagnostic",
                                params
                            ))
                        ).unsafe

                    // lifecycle

                    def workDoneProgressCancel(params: LspHandler.WorkDoneProgressCancelParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Unit, Abort[Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(notifyEffect("window/workDoneProgress/cancel", params))
                        ).unsafe

                    def setTrace(params: LspHandler.SetTraceParams)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]] =
                        Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped(notifyEffect("$/setTrace", params))
                        ).unsafe

                    def cancel(id: JsonRpcId)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]] =
                        Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(handler.cancel(id))).unsafe

                    // session properties

                    def specVersion: String = LspConfig.SpecVersion

                    def positionEncoding: LspHandler.PositionEncodingKind =
                        negotiatedEncodingRef.unsafe.get()(using AllowUnsafe.embrace.danger)

                    def serverCapabilities: Maybe[LspCapabilities.Server.Server] =
                        serverCapsRef.unsafe.get()(using AllowUnsafe.embrace.danger)

                    def serverInfo: Maybe[LspInfo] =
                        serverInfoRef.unsafe.get()(using AllowUnsafe.embrace.danger)

                    def underlying: JsonRpcHandler = handler

                    def awaitDrain(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
                        Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(handler.awaitDrain)).unsafe

                    def close(gracePeriod: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
                        Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(handler.close(gracePeriod))).unsafe

                end unsafe

                // Populate clientRef so lifted client handlers can bind it into Lsp.local.
                clientRef.unsafe.set(Present(unsafe))(using AllowUnsafe.embrace.danger)
                unsafe
            }
        }
    end initClient

end LspClientEngine
