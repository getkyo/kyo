package kyo.internal.lsp

import kyo.*

/** Composes all LSP engine components into a live `LspServer.Unsafe` instance.
  *
  * Wiring order:
  *   1. Build `LspCatalog` from user handlers (throws synchronously for direction/duplicate errors).
  *   2. Auto-derive or use declared `LspCapabilities.Server`.
  *   3. Create `LspDocumentRegistryImpl` with the initial encoding (UTF-16; updated after handshake).
  *   4. Build gate chain (handshake -> shutdown -> capability).
  *   5. Install LSP policies on `config.jsonRpc` (cancellation, progress, unknownMethod).
  *   6. Build built-in routes (initialize, initialized, shutdown, exit, setTrace, sync routes).
  *   7. Lift user handlers via `LspHandlerLift.liftServer`.
  *   8. Call `JsonRpcHandler.initUnscoped` with all routes.
  *   9. Return the concrete `LspServer.Unsafe` anonymous implementation.
  */
private[kyo] object LspEngine:

    // Wire shapes for reverse-direction calls.
    final private case class EmptyParams() derives Schema
    final private case class WorkspaceFoldersResult(workspaceFolders: Maybe[Chunk[LspHandler.WorkspaceFolder]] = Absent) derives Schema
    final private case class ConfigurationResult[T](result: Chunk[T])
    final private case class LogTraceParams(message: String, verbose: Maybe[String] = Absent) derives Schema
    final private case class ProgressParams(token: LspHandler.ProgressToken, value: LspHandler.WorkDoneProgressValue) derives Schema

    /** The session state refs an LSP server engine threads through its routes and gates. The two
      * forward refs (`serverRef`, `handlerRef`) are populated once the handler is constructed.
      */
    final private case class ServerRefs(
        negotiatedEncodingRef: AtomicRef[LspHandler.PositionEncodingKind],
        clientCapabilitiesRef: AtomicRef[Maybe[LspCapabilities.Client.Client]],
        clientInfoRef: AtomicRef[Maybe[LspInfo]],
        workspaceFoldersRef: AtomicRef[Maybe[Chunk[LspHandler.WorkspaceFolder]]],
        traceRef: AtomicRef[LspHandler.TraceValue],
        serverRef: AtomicRef[Maybe[LspServer.Unsafe]],
        handlerRef: AtomicRef[Maybe[JsonRpcHandler]],
        registryImpl: LspDocumentRegistryImpl,
        handshakeGate: JsonRpcMessageGate,
        shutdownGate: JsonRpcMessageGate
    )

    private def initServerRefs(using Frame): ServerRefs < Sync =
        for
            negotiatedEncodingRef <- AtomicRef.init[LspHandler.PositionEncodingKind](LspHandler.PositionEncodingKind.UTF16)
            clientCapabilitiesRef <- AtomicRef.init[Maybe[LspCapabilities.Client.Client]](Absent)
            clientInfoRef         <- AtomicRef.init[Maybe[LspInfo]](Absent)
            workspaceFoldersRef   <- AtomicRef.init[Maybe[Chunk[LspHandler.WorkspaceFolder]]](Absent)
            traceRef              <- AtomicRef.init[LspHandler.TraceValue](LspHandler.TraceValue.Off)
            serverRef             <- AtomicRef.init[Maybe[LspServer.Unsafe]](Absent)
            handlerRef            <- AtomicRef.init[Maybe[JsonRpcHandler]](Absent)
            registryImpl          <- LspDocumentRegistryImpl.init(negotiatedEncodingRef)
            handshakeGate         <- LspHandshakeGate.server()
            shutdownGate          <- LspShutdownGate.server(handlerRef)
        yield ServerRefs(
            negotiatedEncodingRef,
            clientCapabilitiesRef,
            clientInfoRef,
            workspaceFoldersRef,
            traceRef,
            serverRef,
            handlerRef,
            registryImpl,
            handshakeGate,
            shutdownGate
        )

    def initServer(
        transport: JsonRpcTransport,
        userHandlers: Seq[LspHandler[?, ?, ?]],
        config: LspConfig
    )(using Frame): LspServer.Unsafe < Async =
        initServerRefs.flatMap { refs =>
            import refs.*

            // --- Catalog (throws synchronously on direction / duplicate / reserved errors) ---
            val catalog    = LspCatalog.fromHandlers(userHandlers, LspHandler.Direction.ServerHandled)
            val serverCaps = catalog.autoDeriveServerCapabilities(config)

            // --- Gates (registryImpl and the handshake/shutdown gates come from initServerRefs) ---
            val capabilityGate = LspCapabilityGate.server(serverCaps, config.enforceCapabilities)
            val composedGate   = LspGate.compose(handshakeGate, shutdownGate, capabilityGate)

            // --- Config with policies installed ---
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

            JsonRpcHandler.initUnscoped(transport, allRoutes, jsonRpcConfig).flatMap { handler =>

                val unsafe: LspServer.Unsafe = new LspServer.Unsafe:

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

                    def showMessage(params: LspHandler.ShowMessageParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Unit, Abort[LspConnectionClosedException]] =
                        Fiber.Unsafe.init(notifyEffect("window/showMessage", params))

                    def showMessageRequest(params: LspHandler.ShowMessageRequestParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Maybe[LspHandler.MessageActionItem], Abort[LspRequestFailure]] =
                        Fiber.Unsafe.init(callEffect[LspHandler.ShowMessageRequestParams, Maybe[LspHandler.MessageActionItem]](
                            "window/showMessageRequest",
                            params
                        ))

                    def showDocument(params: LspHandler.ShowDocumentParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[LspHandler.ShowDocumentResult, Abort[LspRequestFailure]] =
                        Fiber.Unsafe.init(callEffect[LspHandler.ShowDocumentParams, LspHandler.ShowDocumentResult](
                            "window/showDocument",
                            params
                        ))

                    def logMessage(params: LspHandler.LogMessageParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Unit, Abort[LspConnectionClosedException]] =
                        Fiber.Unsafe.init(notifyEffect("window/logMessage", params))

                    def createWorkDoneProgress(params: LspHandler.WorkDoneProgressCreateParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Unit, Abort[LspRequestFailure]] =
                        Fiber.Unsafe.init(
                            callEffect[LspHandler.WorkDoneProgressCreateParams, EmptyParams](
                                "window/workDoneProgress/create",
                                params
                            ).andThen(())
                        )

                    def telemetry[T](payload: T)(using
                        AllowUnsafe,
                        Frame,
                        Schema[T]
                    ): Fiber.Unsafe[Unit, Abort[LspConnectionClosedException]] =
                        Fiber.Unsafe.init(notifyEffect("telemetry/event", payload))

                    def applyEdit(params: LspHandler.ApplyWorkspaceEditParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[LspHandler.ApplyWorkspaceEditResult, Abort[LspRequestFailure]] =
                        Fiber.Unsafe.init(callEffect[LspHandler.ApplyWorkspaceEditParams, LspHandler.ApplyWorkspaceEditResult](
                            "workspace/applyEdit",
                            params
                        ))

                    def getConfiguration[T](params: LspHandler.ConfigurationParams)(using
                        AllowUnsafe,
                        Frame,
                        Schema[T]
                    ): Fiber.Unsafe[Chunk[T], Abort[LspRequestFailure]] =
                        Fiber.Unsafe.init(callEffect[LspHandler.ConfigurationParams, Chunk[T]]("workspace/configuration", params))

                    def getWorkspaceFolders(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Maybe[Chunk[LspHandler.WorkspaceFolder]], Abort[LspRequestFailure]] =
                        Fiber.Unsafe.init(
                            callEffect[EmptyParams, WorkspaceFoldersResult]("workspace/workspaceFolders", EmptyParams())
                                .map(_.workspaceFolders)
                        )

                    def refreshSemanticTokens(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[LspRequestFailure]] =
                        Fiber.Unsafe.init(callEffect[EmptyParams, EmptyParams](
                            "workspace/semanticTokens/refresh",
                            EmptyParams()
                        ).andThen(()))

                    def refreshInlineValue(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[LspRequestFailure]] =
                        Fiber.Unsafe.init(callEffect[EmptyParams, EmptyParams]("workspace/inlineValue/refresh", EmptyParams()).andThen(()))

                    def refreshInlayHint(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[LspRequestFailure]] =
                        Fiber.Unsafe.init(callEffect[EmptyParams, EmptyParams]("workspace/inlayHint/refresh", EmptyParams()).andThen(()))

                    def refreshDiagnostic(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[LspRequestFailure]] =
                        Fiber.Unsafe.init(callEffect[EmptyParams, EmptyParams]("workspace/diagnostic/refresh", EmptyParams()).andThen(()))

                    def refreshCodeLens(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[LspRequestFailure]] =
                        Fiber.Unsafe.init(callEffect[EmptyParams, EmptyParams]("workspace/codeLens/refresh", EmptyParams()).andThen(()))

                    def registerCapability(params: LspHandler.RegistrationParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Unit, Abort[LspRequestFailure]] =
                        Fiber.Unsafe.init(callEffect[LspHandler.RegistrationParams, EmptyParams](
                            "client/registerCapability",
                            params
                        ).andThen(()))

                    def unregisterCapability(params: LspHandler.UnregistrationParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Unit, Abort[LspRequestFailure]] =
                        Fiber.Unsafe.init(callEffect[LspHandler.UnregistrationParams, EmptyParams](
                            "client/unregisterCapability",
                            params
                        ).andThen(()))

                    def publishDiagnostics(params: LspHandler.PublishDiagnosticsParams)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Unit, Abort[LspConnectionClosedException]] =
                        Fiber.Unsafe.init(notifyEffect("textDocument/publishDiagnostics", params))

                    def logTrace(message: String, verbose: Maybe[String])(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Unit, Abort[LspConnectionClosedException]] =
                        Fiber.Unsafe.init(notifyEffect("$/logTrace", LogTraceParams(message, verbose)))

                    def workDoneProgress(token: LspHandler.ProgressToken, value: LspHandler.WorkDoneProgressValue)(using
                        AllowUnsafe,
                        Frame
                    ): Fiber.Unsafe[Unit, Abort[LspConnectionClosedException]] =
                        Fiber.Unsafe.init(notifyEffect("$/progress", ProgressParams(token, value)))

                    def cancel(id: JsonRpcId)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[LspConnectionClosedException]] =
                        Fiber.Unsafe.init(
                            handler.cancel(id).handle(Abort.recover[Closed] { _ => Abort.fail(LspConnectionClosedException()) })
                        )

                    def specVersion: String = LspConfig.SpecVersion

                    def positionEncoding(using Frame): LspHandler.PositionEncodingKind < Sync =
                        negotiatedEncodingRef.get

                    def clientCapabilities(using Frame): Maybe[LspCapabilities.Client.Client] < Sync =
                        clientCapabilitiesRef.get

                    def clientInfo(using Frame): Maybe[LspInfo] < Sync =
                        clientInfoRef.get

                    def workspaceFolders(using Frame): Maybe[Chunk[LspHandler.WorkspaceFolder]] < Sync =
                        workspaceFoldersRef.get

                    def underlying: JsonRpcHandler = handler

                    def awaitDrain(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
                        Fiber.Unsafe.init(handler.awaitDrain)

                    def close(gracePeriod: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
                        Fiber.Unsafe.init(handler.close(gracePeriod))

                end unsafe

                // Populate the forward refs so the shutdown gate and each lift can reach the
                // handler and the server, then yield the server handle.
                handlerRef.set(Present(handler))
                    .andThen(serverRef.set(Present(unsafe)))
                    .andThen(unsafe)
            }
        }
    end initServer

end LspEngine

// LspDocumentRegistryImpl is declared in Lsp.scala (same file as the sealed DocumentRegistry trait).
// It is accessible here via private[kyo] visibility.
