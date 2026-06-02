package kyo.internal.lsp

import kyo.*

/** Composes all LSP engine components into a live `LspServer.Unsafe` instance.
  *
  * Wiring order (mirrors McpEngine.initServer):
  *   1. Build `LspCatalog` from user handlers (throws synchronously for direction/duplicate errors).
  *   2. Auto-derive or use declared `LspCapabilities.Server`.
  *   3. Create `LspDocumentRegistryImpl` with the initial encoding (UTF-16; updated after handshake).
  *   4. Build gate chain (handshake -> shutdown -> capability) per INV-059.
  *   5. Install LSP policies on `config.jsonRpc` (cancellation, progress, unknownMethod) per INV-030.
  *   6. Build built-in routes (initialize, initialized, shutdown, exit, setTrace, sync routes).
  *   7. Lift user handlers via `LspHandlerLift.liftServer`.
  *   8. Call `JsonRpcHandler.initUnscoped` with all routes.
  *   9. Return the concrete `LspServer.Unsafe` anonymous implementation.
  *
  * Per INV-030, INV-031, INV-049, INV-059, INV-091.
  */
private[kyo] object LspEngine:

    // Wire shapes for reverse-direction calls.
    final private case class EmptyParams() derives Schema
    final private case class WorkspaceFoldersResult(workspaceFolders: Maybe[Chunk[LspHandler.WorkspaceFolder]] = Absent) derives Schema
    final private case class ConfigurationResult[T](result: Chunk[T])
    final private case class LogTraceParams(message: String, verbose: Maybe[String] = Absent) derives Schema
    final private case class ProgressParams(token: LspHandler.ProgressToken, value: LspHandler.WorkDoneProgressValue) derives Schema

    def initServer(
        transport: JsonRpcTransport,
        userHandlers: Seq[LspHandler[?, ?, ?]],
        config: LspConfig
    )(using Frame): LspServer.Unsafe < Async =
        // --- State refs ---
        // AllowUnsafe: AtomicRef for shared state across handler fibers.
        val negotiatedEncodingRef = AtomicRef.Unsafe.init[LspHandler.PositionEncodingKind](
            LspHandler.PositionEncodingKind.UTF16
        )(using AllowUnsafe.embrace.danger).safe
        val clientCapabilitiesRef = AtomicRef.Unsafe.init[Maybe[LspCapabilities.Client.Client]](Absent)(
            using AllowUnsafe.embrace.danger
        ).safe
        val clientInfoRef = AtomicRef.Unsafe.init[Maybe[LspInfo]](Absent)(using AllowUnsafe.embrace.danger).safe
        val workspaceFoldersRef = AtomicRef.Unsafe.init[Maybe[Chunk[LspHandler.WorkspaceFolder]]](Absent)(
            using AllowUnsafe.embrace.danger
        ).safe
        val traceRef = AtomicRef.Unsafe.init[LspHandler.TraceValue](LspHandler.TraceValue.Off)(
            using AllowUnsafe.embrace.danger
        ).safe
        // Forward references populated synchronously after construction.
        val serverRef  = AtomicRef.Unsafe.init[Maybe[LspServer.Unsafe]](Absent)(using AllowUnsafe.embrace.danger).safe
        val handlerRef = AtomicRef.Unsafe.init[Maybe[JsonRpcHandler]](Absent)(using AllowUnsafe.embrace.danger).safe

        // --- Catalog (throws synchronously on direction / duplicate / reserved errors) ---
        val catalog    = LspCatalog.fromHandlers(userHandlers, LspHandler.Direction.ServerHandled)
        val serverCaps = catalog.autoDeriveServerCapabilities(config)

        // --- Document registry (starts with UTF-16; updated at handshake time) ---
        // The registry reads negotiatedEncodingRef on every insert so encoding is always current.
        val registryImpl = new LspDocumentRegistryImpl(negotiatedEncodingRef)

        // --- Gates ---
        val handshakeGate  = LspHandshakeGate.server()
        val shutdownGate   = LspShutdownGate.server(handlerRef)
        val capabilityGate = LspCapabilityGate.server(serverCaps, config.enforceCapabilities)
        val composedGate   = LspGate.compose(handshakeGate, shutdownGate, capabilityGate)

        // --- Config with policies installed (INV-030) ---
        val jsonRpcConfig = config.jsonRpc
            .cancellation(LspCancellationPolicy.default)
            .progress(LspProgressPolicy.default)
            .unknownMethod(JsonRpcUnknownMethodPolicy.minimal)
            .gate(composedGate)

        // --- Built-in routes ---
        val initRoute = LspBuiltInRoutes.initialize(
            config,
            serverCaps,
            negotiatedEncodingRef,
            clientCapabilitiesRef,
            clientInfoRef,
            workspaceFoldersRef
        )
        val initializedRoute = LspBuiltInRoutes.initialized()
        val shutdownRoute    = LspBuiltInRoutes.shutdown()
        val exitRoute        = LspBuiltInRoutes.exit()
        val setTraceRoute    = LspBuiltInRoutes.setTrace(traceRef)

        val didOpenRoute = LspBuiltInRoutes.textDocumentDidOpen(
            registryImpl,
            catalog.handlerFor(LspHandler.Kind.TextDocumentDidOpen),
            serverRef,
            negotiatedEncodingRef
        )
        val didChangeRoute = LspBuiltInRoutes.textDocumentDidChange(
            registryImpl,
            catalog.handlerFor(LspHandler.Kind.TextDocumentDidChange),
            serverRef,
            negotiatedEncodingRef
        )
        val didSaveRoute = LspBuiltInRoutes.textDocumentDidSave(
            registryImpl,
            catalog.handlerFor(LspHandler.Kind.TextDocumentDidSave),
            serverRef,
            negotiatedEncodingRef
        )
        val didCloseRoute = LspBuiltInRoutes.textDocumentDidClose(
            registryImpl,
            catalog.handlerFor(LspHandler.Kind.TextDocumentDidClose),
            serverRef,
            negotiatedEncodingRef
        )
        val willSaveRoute = LspBuiltInRoutes.textDocumentWillSave(
            catalog.handlerFor(LspHandler.Kind.TextDocumentWillSave),
            serverRef,
            registryImpl,
            negotiatedEncodingRef
        )

        val nbDidOpenRoute   = LspBuiltInRoutes.notebookDocumentDidOpen(registryImpl, Absent, serverRef, negotiatedEncodingRef)
        val nbDidChangeRoute = LspBuiltInRoutes.notebookDocumentDidChange(registryImpl)
        val nbDidSaveRoute   = LspBuiltInRoutes.notebookDocumentDidSave(registryImpl)
        val nbDidCloseRoute  = LspBuiltInRoutes.notebookDocumentDidClose(registryImpl)

        // --- Sync Kinds that are handled by built-in routes (not lifted from user handlers) ---
        val syncKinds = Set(
            LspHandler.Kind.TextDocumentDidOpen,
            LspHandler.Kind.TextDocumentDidChange,
            LspHandler.Kind.TextDocumentDidSave,
            LspHandler.Kind.TextDocumentDidClose,
            LspHandler.Kind.TextDocumentWillSave,
            LspHandler.Kind.NotebookDidOpen,
            LspHandler.Kind.NotebookDidChange,
            LspHandler.Kind.NotebookDidSave,
            LspHandler.Kind.NotebookDidClose
        )

        // --- Lift user handlers (skip sync kinds handled by built-in routes) ---
        val userRoutes: Seq[JsonRpcRoute[?, ?, ?]] = userHandlers
            .filterNot(h => syncKinds.contains(h.kind))
            .map(h => LspHandlerLift.liftServer(h, serverRef, registryImpl, negotiatedEncodingRef))

        val allRoutes: Seq[JsonRpcRoute[?, ?, ?]] = Seq(
            initRoute,
            initializedRoute,
            shutdownRoute,
            exitRoute,
            setTraceRoute,
            didOpenRoute,
            didChangeRoute,
            didSaveRoute,
            didCloseRoute,
            willSaveRoute,
            nbDidOpenRoute,
            nbDidChangeRoute,
            nbDidSaveRoute,
            nbDidCloseRoute
        ) ++ userRoutes

        JsonRpcHandler.initUnscoped(transport, allRoutes, jsonRpcConfig).map { handler =>
            // Populate handlerRef so the shutdown gate can trigger close.
            handlerRef.unsafe.set(Present(handler))(using AllowUnsafe.embrace.danger)

            val unsafe: LspServer.Unsafe = new LspServer.Unsafe:

                private def notifyEffect[In: Schema](method: String, params: In)(using Frame): Unit < (Async & Abort[Closed]) =
                    handler.notify[In](method, params)

                private def callEffect[In: Schema, Out: Schema](method: String, params: In)(using
                    Frame
                ): Out < (Async & Abort[LspException | Closed]) =
                    handler.call[In, Out](method, params)
                        .handle(Abort.recover[JsonRpcError] { e =>
                            Abort.fail(LspException.Application.Remote(e.code, e.message, e.data))
                        })

                def showMessage(params: LspHandler.ShowMessageParams)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]] =
                    Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(notifyEffect("window/showMessage", params))).unsafe

                def showMessageRequest(params: LspHandler.ShowMessageRequestParams)(using
                    AllowUnsafe,
                    Frame
                ): Fiber.Unsafe[Maybe[LspHandler.MessageActionItem], Abort[LspException | Closed]] =
                    Sync.Unsafe.evalOrThrow(
                        Fiber.initUnscoped(callEffect[LspHandler.ShowMessageRequestParams, Maybe[LspHandler.MessageActionItem]](
                            "window/showMessageRequest",
                            params
                        ))
                    ).unsafe

                def showDocument(params: LspHandler.ShowDocumentParams)(using
                    AllowUnsafe,
                    Frame
                ): Fiber.Unsafe[LspHandler.ShowDocumentResult, Abort[LspException | Closed]] =
                    Sync.Unsafe.evalOrThrow(
                        Fiber.initUnscoped(callEffect[LspHandler.ShowDocumentParams, LspHandler.ShowDocumentResult](
                            "window/showDocument",
                            params
                        ))
                    ).unsafe

                def logMessage(params: LspHandler.LogMessageParams)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]] =
                    Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(notifyEffect("window/logMessage", params))).unsafe

                def createWorkDoneProgress(params: LspHandler.WorkDoneProgressCreateParams)(using
                    AllowUnsafe,
                    Frame
                ): Fiber.Unsafe[Unit, Abort[LspException | Closed]] =
                    Sync.Unsafe.evalOrThrow(
                        Fiber.initUnscoped(
                            callEffect[LspHandler.WorkDoneProgressCreateParams, EmptyParams](
                                "window/workDoneProgress/create",
                                params
                            ).andThen(())
                        )
                    ).unsafe

                def telemetry[T](payload: T)(using AllowUnsafe, Frame, Schema[T]): Fiber.Unsafe[Unit, Abort[Closed]] =
                    Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(notifyEffect("telemetry/event", payload))).unsafe

                def applyEdit(params: LspHandler.ApplyWorkspaceEditParams)(using
                    AllowUnsafe,
                    Frame
                ): Fiber.Unsafe[LspHandler.ApplyWorkspaceEditResult, Abort[LspException | Closed]] =
                    Sync.Unsafe.evalOrThrow(
                        Fiber.initUnscoped(callEffect[LspHandler.ApplyWorkspaceEditParams, LspHandler.ApplyWorkspaceEditResult](
                            "workspace/applyEdit",
                            params
                        ))
                    ).unsafe

                def getConfiguration[T](params: LspHandler.ConfigurationParams)(using
                    AllowUnsafe,
                    Frame,
                    Schema[T]
                ): Fiber.Unsafe[Chunk[T], Abort[LspException | Closed]] =
                    Sync.Unsafe.evalOrThrow(
                        Fiber.initUnscoped(callEffect[LspHandler.ConfigurationParams, Chunk[T]]("workspace/configuration", params))
                    ).unsafe

                def getWorkspaceFolders(using
                    AllowUnsafe,
                    Frame
                ): Fiber.Unsafe[Maybe[Chunk[LspHandler.WorkspaceFolder]], Abort[LspException | Closed]] =
                    Sync.Unsafe.evalOrThrow(
                        Fiber.initUnscoped(
                            callEffect[EmptyParams, WorkspaceFoldersResult]("workspace/workspaceFolders", EmptyParams())
                                .map(_.workspaceFolders)
                        )
                    ).unsafe

                def refreshSemanticTokens(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[LspException | Closed]] =
                    Sync.Unsafe.evalOrThrow(
                        Fiber.initUnscoped(callEffect[EmptyParams, EmptyParams](
                            "workspace/semanticTokens/refresh",
                            EmptyParams()
                        ).andThen(()))
                    ).unsafe

                def refreshInlineValue(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[LspException | Closed]] =
                    Sync.Unsafe.evalOrThrow(
                        Fiber.initUnscoped(callEffect[EmptyParams, EmptyParams]("workspace/inlineValue/refresh", EmptyParams()).andThen(()))
                    ).unsafe

                def refreshInlayHint(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[LspException | Closed]] =
                    Sync.Unsafe.evalOrThrow(
                        Fiber.initUnscoped(callEffect[EmptyParams, EmptyParams]("workspace/inlayHint/refresh", EmptyParams()).andThen(()))
                    ).unsafe

                def refreshDiagnostic(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[LspException | Closed]] =
                    Sync.Unsafe.evalOrThrow(
                        Fiber.initUnscoped(callEffect[EmptyParams, EmptyParams]("workspace/diagnostic/refresh", EmptyParams()).andThen(()))
                    ).unsafe

                def refreshCodeLens(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[LspException | Closed]] =
                    Sync.Unsafe.evalOrThrow(
                        Fiber.initUnscoped(callEffect[EmptyParams, EmptyParams]("workspace/codeLens/refresh", EmptyParams()).andThen(()))
                    ).unsafe

                def registerCapability(params: LspHandler.RegistrationParams)(using
                    AllowUnsafe,
                    Frame
                ): Fiber.Unsafe[Unit, Abort[LspException | Closed]] =
                    Sync.Unsafe.evalOrThrow(
                        Fiber.initUnscoped(callEffect[LspHandler.RegistrationParams, EmptyParams](
                            "client/registerCapability",
                            params
                        ).andThen(()))
                    ).unsafe

                def unregisterCapability(params: LspHandler.UnregistrationParams)(using
                    AllowUnsafe,
                    Frame
                ): Fiber.Unsafe[Unit, Abort[LspException | Closed]] =
                    Sync.Unsafe.evalOrThrow(
                        Fiber.initUnscoped(callEffect[LspHandler.UnregistrationParams, EmptyParams](
                            "client/unregisterCapability",
                            params
                        ).andThen(()))
                    ).unsafe

                def publishDiagnostics(params: LspHandler.PublishDiagnosticsParams)(using
                    AllowUnsafe,
                    Frame
                ): Fiber.Unsafe[Unit, Abort[Closed]] =
                    Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(notifyEffect("textDocument/publishDiagnostics", params))).unsafe

                def logTrace(message: String, verbose: Maybe[String])(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]] =
                    Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(notifyEffect("$/logTrace", LogTraceParams(message, verbose)))).unsafe

                def workDoneProgress(token: LspHandler.ProgressToken, value: LspHandler.WorkDoneProgressValue)(using
                    AllowUnsafe,
                    Frame
                ): Fiber.Unsafe[Unit, Abort[Closed]] =
                    Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(notifyEffect("$/progress", ProgressParams(token, value)))).unsafe

                def cancel(id: JsonRpcId)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]] =
                    Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(handler.cancel(id))).unsafe

                def specVersion: String = LspConfig.SpecVersion

                def positionEncoding: LspHandler.PositionEncodingKind =
                    negotiatedEncodingRef.unsafe.get()(using AllowUnsafe.embrace.danger)

                def clientCapabilities: Maybe[LspCapabilities.Client.Client] =
                    clientCapabilitiesRef.unsafe.get()(using AllowUnsafe.embrace.danger)

                def clientInfo: Maybe[LspInfo] =
                    clientInfoRef.unsafe.get()(using AllowUnsafe.embrace.danger)

                def workspaceFolders: Maybe[Chunk[LspHandler.WorkspaceFolder]] =
                    workspaceFoldersRef.unsafe.get()(using AllowUnsafe.embrace.danger)

                def underlying: JsonRpcHandler = handler

                def awaitDrain(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
                    Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(handler.awaitDrain)).unsafe

                def close(gracePeriod: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
                    Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(handler.close(gracePeriod))).unsafe

            end unsafe

            // Populate server forward ref so each lift can bind it into Lsp.local.
            serverRef.unsafe.set(Present(unsafe))(using AllowUnsafe.embrace.danger)
            unsafe
        }
    end initServer

end LspEngine

// LspDocumentRegistryImpl is declared in Lsp.scala (same file as the sealed DocumentRegistry trait).
// It is accessible here via private[kyo] visibility.
