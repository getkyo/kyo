package kyo.internal.mcp

import kyo.*

/** Wires the MCP client handshake and wraps the underlying [[JsonRpcHandler]] in a concrete
  * [[McpClient.Unsafe]] implementation.
  *
  * Initialization order:
  *   1. Build client-side reverse-direction routes via [[McpReverseDispatch]].
  *   2. Start [[JsonRpcHandler.initUnscoped]] with those routes and the supplied config.
  *   3. Issue the `initialize` request; await the [[McpInitializeResult]] response.
  *   4. Persist negotiated state into [[AtomicRef]] holders.
  *   5. Send `notifications/initialized`.
  *   6. Return the concrete [[McpClient.Unsafe]] wrapping the live handler.
  *
  * The returned [[McpClient.Unsafe]] is safe to use immediately: all `AtomicRef` holders are
  * populated before the instance is yielded.
  */
private[kyo] object McpClientEngine:

    // Wire type for the notifications/initialized outbound notification.
    // Empty case class encodes as {} per kyo-schema, matching MCP spec.
    final private case class InitializedParams() derives Schema

    // Wire type for client-to-server outbound notifications that carry no params.
    final private case class NotifyEmptyParams() derives Schema

    // Wire types for tools/call request.
    final private case class ToolCallRequest(name: String, arguments: Structure.Value) derives Schema

    // Wire types for resources/read request.
    final private case class ReadResourceRequest(uri: String) derives Schema
    final private case class ReadResourceResponse(contents: Chunk[McpHandler.ResourceContents]) derives Schema

    // Wire types for prompts/get request.
    final private case class GetPromptRequest(name: String, arguments: Map[String, String] = Map.empty) derives Schema

    // Wire type for logging/setLevel request.
    final private case class SetLogLevelRequest(level: String) derives Schema
    final private case class SetLogLevelResponse() derives Schema

    // Wire types for list methods.
    final private case class ListRequest(cursor: Maybe[String] = Absent) derives Schema
    final private case class ToolsListResponse(tools: Chunk[McpHandler.ToolMeta], nextCursor: Maybe[String] = Absent)
        derives Schema
    final private case class ResourcesListResponse(resources: Chunk[McpHandler.ResourceMeta], nextCursor: Maybe[String] = Absent)
        derives Schema
    final private case class ResourceTemplatesListResponse(
        resourceTemplates: Chunk[McpHandler.ResourceTemplateMeta],
        nextCursor: Maybe[String] = Absent
    ) derives Schema
    final private case class PromptsListResponse(prompts: Chunk[McpHandler.PromptMeta], nextCursor: Maybe[String] = Absent)
        derives Schema

    // Wire types for completion/complete.
    final private case class CompleteRequest(ref: McpHandler.CompletionRef, argument: McpHandler.CompletionArg) derives Schema
    final private case class CompleteResponse(completion: McpHandler.CompletionOutcome) derives Schema

    // Wire types for resources/subscribe and resources/unsubscribe.
    final private case class SubscribeRequest(uri: String) derives Schema
    final private case class SubscribeResponse() derives Schema

    /** Initializes the MCP client over `transport`.
      *
      * Issues the `initialize` request, awaits the response, persists negotiated state, sends
      * `notifications/initialized`, then returns a concrete [[McpClient.Unsafe]] wrapping the
      * live handler. The returned instance's state accessors (`serverCapabilitiesUnsafe`, etc.)
      * are populated before the instance is returned.
      *
      * @param transport    the transport to communicate over
      * @param clientInfo   the client identification to send in the `initialize` request
      * @param capabilities the client capabilities to advertise
      * @param routes       user-registered reverse-direction routes (sampling, roots, elicitation handlers)
      * @param config       MCP configuration
      * @return a live [[McpClient.Unsafe]] after the handshake completes
      */
    def initClient(
        transport: JsonRpcTransport,
        clientInfo: McpInfo,
        capabilities: McpCapabilities.Client,
        handlers: Seq[McpClientHandler[?, ?, ?]],
        config: McpConfig
    )(using Frame): McpClient.Unsafe < (Async & Abort[McpInitFailure]) =
        // Unsafe: three AtomicRef for negotiated state shared across handler fibers.
        val serverCapabilitiesRef = AtomicRef.Unsafe.init[Maybe[McpCapabilities.Server]](Absent)(using AllowUnsafe.embrace.danger).safe
        val serverInfoRef         = AtomicRef.Unsafe.init[Maybe[McpInfo]](Absent)(using AllowUnsafe.embrace.danger).safe
        val negotiatedVersionRef  = AtomicRef.Unsafe.init[Maybe[McpConfig.ProtocolVersion]](Absent)(using AllowUnsafe.embrace.danger).safe
        // Unsafe: forward reference holding the sentinel "server" used to bind into Mcp.local
        // during client-side reverse-direction dispatch.
        val serverRef = AtomicRef.Unsafe.init[Maybe[McpServer.Unsafe]](Absent)(using AllowUnsafe.embrace.danger).safe

        val reverseRoutes = McpReverseDispatch.buildRoutes(handlers, capabilities, serverRef)

        // Map any transport `Closed` from the handshake exchange (initUnscoped, the initialize call,
        // the notifications/initialized notify) to the typed McpConnectionClosedException so the
        // public init row stays McpInitFailure with no bare Closed.
        Abort.recover[Closed](_ => Abort.fail(McpConnectionClosedException()))(
            for
                handler <- JsonRpcHandler.initUnscoped(transport, reverseRoutes, config.jsonRpc)
                // Enforce the handshake timeout around the initialize exchange. A server that
                // never completes the handshake within config.handshakeTimeout aborts rather than hanging.
                // Timeout is converted to McpHandshakeNotInitializedException so the row stays McpInitFailure.
                initResult <-
                    val callEffect: McpInitializeResult < (Async & Abort[McpInitFailure | Closed]) =
                        Abort.recover[JsonRpcError](e => Abort.fail(McpHandshakeNotInitializedException(e.message): McpInitFailure))(
                            handler.call[McpInitializeRequest, McpInitializeResult](
                                "initialize",
                                McpInitializeRequest(McpConfig.ProtocolVersion.current, clientInfo, capabilities)
                            )
                        )
                    Abort.recover[Timeout](_ =>
                        Abort.fail(McpHandshakeNotInitializedException(
                            s"initialize: no response from server within ${config.handshakeTimeout}"
                        ): McpInitFailure)
                    )(
                        Async.timeout(config.handshakeTimeout)(callEffect)
                    )
                _ <- negotiatedVersionRef.set(Present(initResult.protocolVersion))
                _ <- serverCapabilitiesRef.set(Present(initResult.capabilities))
                _ <- serverInfoRef.set(Present(initResult.serverInfo))
                _ <- handler.notify[InitializedParams]("notifications/initialized", InitializedParams())
            yield
                val unsafe = buildUnsafe(handler, serverCapabilitiesRef, serverInfoRef, negotiatedVersionRef)
                // Publish the sentinel server (which rejects reverse-direction calls) so that client-side
                // reverse-direction handlers can still bind a context into Mcp.local without panic.
                // Unsafe: synchronous write of forward reference immediately after construction.
                val sentinelServer = buildClientSentinelServer(handler)
                serverRef.unsafe.set(Present(sentinelServer))(using AllowUnsafe.embrace.danger)
                unsafe
        )
    end initClient

    /** Builds a no-op McpServer.Unsafe sentinel for client-side route carriers.
      *
      * Client-side route handlers (sampling, elicitation, roots) receive a [[JsonRpcRoute.Context]]
      * carrying the inbound request envelope. They do not have an `McpServer` (no reverse-direction
      * peer in client mode), so we provide a sentinel that aborts with a clear error if a handler
      * accidentally reaches into `Mcp.server` for a reverse-direction call.
      * The handler itself must not call `ctx.server`; this sentinel exists solely to satisfy the
      * carrier's serverRef requirement and avoid IllegalStateException during dispatch.
      */
    private def buildClientSentinelServer(handler: JsonRpcHandler): McpServer.Unsafe =
        new McpServer.Unsafe:
            def requestSampling(request: McpServer.SamplingRequest)(using
                AllowUnsafe,
                Frame
            ): Fiber.Unsafe[McpServer.SamplingResponse, Abort[McpRequestSamplingFailure]] =
                // Unsafe: detach-then-reattach bridge; the inner effect runs on a fresh unscoped
                // fiber so the caller's Scope does not cancel it when the handler returns.
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(
                    Abort.fail(McpSamplingRejectedException(
                        "ctx.server.requestSampling is not available in client-side route handlers"
                    )): McpServer.SamplingResponse < (Async & Abort[McpRequestSamplingFailure])
                )).unsafe

            def requestRoots(using AllowUnsafe, Frame): Fiber.Unsafe[Chunk[McpServer.Root], Abort[McpRequestRootsFailure]] =
                // Unsafe: detach-then-reattach bridge; the inner effect runs on a fresh unscoped
                // fiber so the caller's Scope does not cancel it when the handler returns.
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(
                    Abort.fail(McpInvalidArgumentException(
                        "roots/list",
                        "server",
                        "ctx.server.requestRoots is not available in client-side route handlers"
                    )): Chunk[McpServer.Root] < (Async & Abort[McpRequestRootsFailure])
                )).unsafe

            def requestElicitation(request: McpServer.ElicitationRequest)(using
                AllowUnsafe,
                Frame
            ): Fiber.Unsafe[McpServer.ElicitationResponse, Abort[McpRequestElicitationFailure]] =
                // Unsafe: detach-then-reattach bridge; the inner effect runs on a fresh unscoped
                // fiber so the caller's Scope does not cancel it when the handler returns.
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(
                    Abort.fail(McpElicitationDeclinedException(
                        "ctx.server.requestElicitation is not available in client-side route handlers"
                    )): McpServer.ElicitationResponse < (Async & Abort[McpRequestElicitationFailure])
                )).unsafe

            def notifyToolsListChanged(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[McpConnectionClosedException]] =
                // Unsafe: detach-then-reattach bridge; the inner effect runs on a fresh unscoped
                // fiber so the caller's Scope does not cancel it when the handler returns.
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(Sync.defer[Unit, Abort[McpConnectionClosedException]](()))).unsafe

            def notifyResourcesListChanged(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[McpConnectionClosedException]] =
                // Unsafe: detach-then-reattach bridge; the inner effect runs on a fresh unscoped
                // fiber so the caller's Scope does not cancel it when the handler returns.
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(Sync.defer[Unit, Abort[McpConnectionClosedException]](()))).unsafe

            def notifyResourceUpdated(uri: McpResourceUri)(using
                AllowUnsafe,
                Frame
            ): Fiber.Unsafe[Unit, Abort[McpConnectionClosedException]] =
                // Unsafe: detach-then-reattach bridge; the inner effect runs on a fresh unscoped
                // fiber so the caller's Scope does not cancel it when the handler returns.
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(Sync.defer[Unit, Abort[McpConnectionClosedException]](()))).unsafe

            def notifyPromptsListChanged(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[McpConnectionClosedException]] =
                // Unsafe: detach-then-reattach bridge; the inner effect runs on a fresh unscoped
                // fiber so the caller's Scope does not cancel it when the handler returns.
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(Sync.defer[Unit, Abort[McpConnectionClosedException]](()))).unsafe

            def notifyLog[T](level: McpServer.LogLevel, data: T, logger: Maybe[String])(using
                AllowUnsafe,
                Frame,
                Schema[T]
            ): Fiber.Unsafe[Unit, Abort[McpConnectionClosedException]] =
                // Unsafe: detach-then-reattach bridge; the inner effect runs on a fresh unscoped
                // fiber so the caller's Scope does not cancel it when the handler returns.
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(Sync.defer[Unit, Abort[McpConnectionClosedException]](()))).unsafe

            def protocolVersion: Maybe[McpConfig.ProtocolVersion] = Absent
            def clientCapabilities: Maybe[McpCapabilities.Client] = Absent
            def clientInfo: Maybe[McpInfo]                        = Absent
            def underlying: JsonRpcHandler                        = handler

            def awaitDrain(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
                // Unsafe: detach-then-reattach bridge; the inner effect runs on a fresh unscoped
                // fiber so the caller's Scope does not cancel it when the handler returns.
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(Sync.defer(()))).unsafe

            def close(gracePeriod: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
                // Unsafe: detach-then-reattach bridge; the inner effect runs on a fresh unscoped
                // fiber so the caller's Scope does not cancel it when the handler returns.
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(Sync.defer(()))).unsafe

            private[kyo] def closeDirect(using Frame): Unit < Async = Sync.defer(())
        end new
    end buildClientSentinelServer

    // Wire type for the complete request that includes the optional context field.
    final private case class CompleteRequestWithContext(
        ref: McpHandler.CompletionRef,
        argument: McpHandler.CompletionArg,
        context: Maybe[McpHandler.CompletionArg.Context] = Absent
    ) derives Schema

    private def buildUnsafe(
        handler: JsonRpcHandler,
        serverCapabilitiesRef: AtomicRef[Maybe[McpCapabilities.Server]],
        serverInfoRef: AtomicRef[Maybe[McpInfo]],
        negotiatedVersionRef: AtomicRef[Maybe[McpConfig.ProtocolVersion]]
    ): McpClient.Unsafe =
        new McpClient.Unsafe:

            // Maps the residual transport `Closed` (the member left after the JsonRpcError recover)
            // to the typed McpConnectionClosedException so no bare `Closed` survives onto a public row.
            private def recoverClosed[A, S](v: A < (S & Abort[Closed]))(using Frame): A < (S & Abort[McpConnectionClosedException]) =
                Abort.recover[Closed](_ => Abort.fail(McpConnectionClosedException()))(v)

            private def listToolsEffect(cursor: Maybe[McpCursor])(using
                Frame
            ): McpClient.Page[McpHandler.ToolMeta] < (Async & Abort[McpListFailure]) =
                recoverClosed(
                    handler.call[ListRequest, ToolsListResponse]("tools/list", ListRequest(cursor.map(_.asString)))
                        .map(r => McpClient.Page(r.tools, r.nextCursor.map(McpCursor.fromWire)))
                        .handle(Abort.recover[JsonRpcError] { e =>
                            Abort.fail(McpRemoteApplicationException(e.code, e.message, e.data))
                        })
                )

            // Produces exactly the two leaves both callTool rows share, so the raw and typed wrappers
            // can each annotate their own operation-trait without a sibling-trait mismatch.
            private def callToolEffect[In](name: String, arguments: In)(using
                Frame,
                Schema[In]
            ): McpHandler.ToolOutcome < (Async & Abort[McpRemoteApplicationException | McpConnectionClosedException]) =
                val encodedArgs = Structure.encode[In](arguments)
                recoverClosed(
                    handler.call[ToolCallRequest, McpHandler.ToolOutcome]("tools/call", ToolCallRequest(name, encodedArgs))
                        .handle(Abort.recover[JsonRpcError] { e =>
                            Abort.fail(McpRemoteApplicationException(e.code, e.message, e.data))
                        })
                )
            end callToolEffect

            private def callToolRawEffect[In](name: String, arguments: In)(using
                Frame,
                Schema[In]
            ): McpHandler.ToolOutcome < (Async & Abort[McpCallToolRawFailure]) =
                callToolEffect[In](name, arguments)

            private def callToolTypedEffect[In, Out](name: String, arguments: In)(using
                Frame,
                Schema[In],
                Schema[Out]
            ): Out < (Async & Abort[McpCallToolFailure]) =
                callToolEffect[In](name, arguments).flatMap { result =>
                    result.structuredContent match
                        case Present(sv) =>
                            Structure.decode[Out](sv) match
                                case Result.Success(out) => Sync.defer(out)
                                case Result.Failure(e)   => Abort.fail(McpToolStructuredDecodeException(name, e.getMessage, Present(e)))
                                case Result.Panic(t)     => Abort.panic(t)
                        case Absent =>
                            Abort.fail(McpToolStructuredMissingException(name))
                }

            private def listResourcesEffect(cursor: Maybe[McpCursor])(using
                Frame
            ): McpClient.Page[McpHandler.ResourceMeta] < (Async & Abort[McpListFailure]) =
                recoverClosed(
                    handler.call[ListRequest, ResourcesListResponse]("resources/list", ListRequest(cursor.map(_.asString)))
                        .map(r => McpClient.Page(r.resources, r.nextCursor.map(McpCursor.fromWire)))
                        .handle(Abort.recover[JsonRpcError] { e =>
                            Abort.fail(McpRemoteApplicationException(e.code, e.message, e.data))
                        })
                )

            private def listResourceTemplatesEffect(cursor: Maybe[McpCursor])(using
                Frame
            ): McpClient.Page[McpHandler.ResourceTemplateMeta] < (Async & Abort[McpListFailure]) =
                recoverClosed(
                    handler.call[ListRequest, ResourceTemplatesListResponse](
                        "resources/templates/list",
                        ListRequest(cursor.map(_.asString))
                    )
                        .map(r => McpClient.Page(r.resourceTemplates, r.nextCursor.map(McpCursor.fromWire)))
                        .handle(Abort.recover[JsonRpcError] { e =>
                            Abort.fail(McpRemoteApplicationException(e.code, e.message, e.data))
                        })
                )

            private def readResourceEffect(uri: McpResourceUri)(using
                Frame
            ): Chunk[McpHandler.ResourceContents] < (Async & Abort[McpReadResourceRawFailure]) =
                recoverClosed(
                    handler.call[ReadResourceRequest, ReadResourceResponse]("resources/read", ReadResourceRequest(uri.asString))
                        .map(_.contents)
                        .handle(Abort.recover[JsonRpcError] { e =>
                            Abort.fail(McpRemoteApplicationException(e.code, e.message, e.data))
                        })
                )

            private def listPromptsEffect(cursor: Maybe[McpCursor])(using
                Frame
            ): McpClient.Page[McpHandler.PromptMeta] < (Async & Abort[McpListFailure]) =
                recoverClosed(
                    handler.call[ListRequest, PromptsListResponse]("prompts/list", ListRequest(cursor.map(_.asString)))
                        .map(r => McpClient.Page(r.prompts, r.nextCursor.map(McpCursor.fromWire)))
                        .handle(Abort.recover[JsonRpcError] { e =>
                            Abort.fail(McpRemoteApplicationException(e.code, e.message, e.data))
                        })
                )

            private def getPromptEffect(name: String, arguments: Map[String, String])(using
                Frame
            ): McpHandler.PromptOutcome < (Async & Abort[McpGetPromptRawFailure]) =
                recoverClosed(
                    handler.call[GetPromptRequest, McpHandler.PromptOutcome]("prompts/get", GetPromptRequest(name, arguments))
                        .handle(Abort.recover[JsonRpcError] { e =>
                            Abort.fail(McpRemoteApplicationException(e.code, e.message, e.data))
                        })
                )

            private def setLogLevelEffect(level: McpServer.LogLevel)(using Frame): Unit < (Async & Abort[McpClientRequestFailure]) =
                recoverClosed(
                    handler.call[SetLogLevelRequest, SetLogLevelResponse](
                        "logging/setLevel",
                        SetLogLevelRequest(level.toString.toLowerCase)
                    )
                        .map(_ => ())
                        .handle(Abort.recover[JsonRpcError] { e =>
                            Abort.fail(McpRemoteApplicationException(e.code, e.message, e.data))
                        })
                )

            private def completeEffect(
                ref: McpHandler.CompletionRef,
                arg: McpHandler.CompletionArg,
                context: Maybe[McpHandler.CompletionArg.Context]
            )(using
                Frame
            ): McpHandler.CompletionOutcome < (Async & Abort[McpCompleteFailure]) =
                recoverClosed(
                    handler.call[CompleteRequestWithContext, CompleteResponse](
                        "completion/complete",
                        CompleteRequestWithContext(ref, arg, context)
                    )
                        .map(_.completion)
                        .handle(Abort.recover[JsonRpcError] { e =>
                            Abort.fail(McpRemoteApplicationException(e.code, e.message, e.data))
                        })
                )

            private def notifyRootsListChangedEffect(using Frame): Unit < (Async & Abort[McpConnectionClosedException]) =
                recoverClosed(handler.notify[NotifyEmptyParams]("notifications/roots/list_changed", NotifyEmptyParams()))

            private def pingEffect(using Frame): Unit < (Async & Abort[McpClientRequestFailure]) =
                recoverClosed(
                    handler.call[NotifyEmptyParams, NotifyEmptyParams]("ping", NotifyEmptyParams())
                        .map(_ => ())
                        .handle(Abort.recover[JsonRpcError] { e =>
                            Abort.fail(McpRemoteApplicationException(e.code, e.message, e.data))
                        })
                )

            private def subscribeResourceEffect(uri: McpResourceUri)(using Frame): Unit < (Async & Abort[McpClientRequestFailure]) =
                recoverClosed(
                    handler.call[SubscribeRequest, SubscribeResponse]("resources/subscribe", SubscribeRequest(uri.asString))
                        .map(_ => ())
                        .handle(Abort.recover[JsonRpcError] { e =>
                            Abort.fail(McpRemoteApplicationException(e.code, e.message, e.data))
                        })
                )

            private def unsubscribeResourceEffect(uri: McpResourceUri)(using Frame): Unit < (Async & Abort[McpClientRequestFailure]) =
                recoverClosed(
                    handler.call[SubscribeRequest, SubscribeResponse]("resources/unsubscribe", SubscribeRequest(uri.asString))
                        .map(_ => ())
                        .handle(Abort.recover[JsonRpcError] { e =>
                            Abort.fail(McpRemoteApplicationException(e.code, e.message, e.data))
                        })
                )

            // --- Public Unsafe interface ---

            def listTools(cursor: Maybe[McpCursor])(using
                AllowUnsafe,
                Frame
            ): Fiber.Unsafe[McpClient.Page[McpHandler.ToolMeta], Abort[McpListFailure]] =
                // Unsafe: detach-then-reattach bridge; the inner effect runs on a fresh unscoped
                // fiber so the caller's Scope does not cancel it when the handler returns.
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(listToolsEffect(cursor))).unsafe

            def callTool[In](name: String, arguments: In)(using
                AllowUnsafe,
                Frame,
                Schema[In]
            ): Fiber.Unsafe[McpHandler.ToolOutcome, Abort[McpCallToolRawFailure]] =
                // Unsafe: detach-then-reattach bridge; the inner effect runs on a fresh unscoped
                // fiber so the caller's Scope does not cancel it when the handler returns.
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(callToolRawEffect[In](name, arguments))).unsafe

            def callToolTyped[In, Out](name: String, arguments: In)(using
                AllowUnsafe,
                Frame,
                Schema[In],
                Schema[Out]
            ): Fiber.Unsafe[Out, Abort[McpCallToolFailure]] =
                // Unsafe: detach-then-reattach bridge; the inner effect runs on a fresh unscoped
                // fiber so the caller's Scope does not cancel it when the handler returns.
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(callToolTypedEffect[In, Out](name, arguments))).unsafe

            def listResources(cursor: Maybe[McpCursor])(using
                AllowUnsafe,
                Frame
            ): Fiber.Unsafe[McpClient.Page[McpHandler.ResourceMeta], Abort[McpListFailure]] =
                // Unsafe: detach-then-reattach bridge; the inner effect runs on a fresh unscoped
                // fiber so the caller's Scope does not cancel it when the handler returns.
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(listResourcesEffect(cursor))).unsafe

            def listResourceTemplates(cursor: Maybe[McpCursor])(using
                AllowUnsafe,
                Frame
            ): Fiber.Unsafe[McpClient.Page[McpHandler.ResourceTemplateMeta], Abort[McpListFailure]] =
                // Unsafe: detach-then-reattach bridge; the inner effect runs on a fresh unscoped
                // fiber so the caller's Scope does not cancel it when the handler returns.
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(listResourceTemplatesEffect(cursor))).unsafe

            def readResource(uri: McpResourceUri)(using
                AllowUnsafe,
                Frame
            ): Fiber.Unsafe[Chunk[McpHandler.ResourceContents], Abort[McpReadResourceRawFailure]] =
                // Unsafe: detach-then-reattach bridge; the inner effect runs on a fresh unscoped
                // fiber so the caller's Scope does not cancel it when the handler returns.
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(readResourceEffect(uri))).unsafe

            def listPrompts(cursor: Maybe[McpCursor])(using
                AllowUnsafe,
                Frame
            ): Fiber.Unsafe[McpClient.Page[McpHandler.PromptMeta], Abort[McpListFailure]] =
                // Unsafe: detach-then-reattach bridge; the inner effect runs on a fresh unscoped
                // fiber so the caller's Scope does not cancel it when the handler returns.
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(listPromptsEffect(cursor))).unsafe

            def getPrompt(name: String, arguments: Map[String, String])(using
                AllowUnsafe,
                Frame
            ): Fiber.Unsafe[McpHandler.PromptOutcome, Abort[McpGetPromptRawFailure]] =
                // Unsafe: detach-then-reattach bridge; the inner effect runs on a fresh unscoped
                // fiber so the caller's Scope does not cancel it when the handler returns.
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(getPromptEffect(name, arguments))).unsafe

            def setLogLevel(level: McpServer.LogLevel)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[McpClientRequestFailure]] =
                // Unsafe: detach-then-reattach bridge; the inner effect runs on a fresh unscoped
                // fiber so the caller's Scope does not cancel it when the handler returns.
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(setLogLevelEffect(level))).unsafe

            def complete(
                ref: McpHandler.CompletionRef,
                arg: McpHandler.CompletionArg,
                context: Maybe[McpHandler.CompletionArg.Context]
            )(using AllowUnsafe, Frame): Fiber.Unsafe[McpHandler.CompletionOutcome, Abort[McpCompleteFailure]] =
                // Unsafe: detach-then-reattach bridge; the inner effect runs on a fresh unscoped
                // fiber so the caller's Scope does not cancel it when the handler returns.
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(completeEffect(ref, arg, context))).unsafe

            def notifyRootsListChanged(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[McpConnectionClosedException]] =
                // Unsafe: detach-then-reattach bridge; the inner effect runs on a fresh unscoped
                // fiber so the caller's Scope does not cancel it when the handler returns.
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(notifyRootsListChangedEffect)).unsafe

            def ping(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[McpClientRequestFailure]] =
                // Unsafe: detach-then-reattach bridge; the inner effect runs on a fresh unscoped
                // fiber so the caller's Scope does not cancel it when the handler returns.
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(pingEffect)).unsafe

            def subscribeResource(uri: McpResourceUri)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[McpClientRequestFailure]] =
                // Unsafe: detach-then-reattach bridge; the inner effect runs on a fresh unscoped
                // fiber so the caller's Scope does not cancel it when the handler returns.
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(subscribeResourceEffect(uri))).unsafe

            def unsubscribeResource(uri: McpResourceUri)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[McpClientRequestFailure]] =
                // Unsafe: detach-then-reattach bridge; the inner effect runs on a fresh unscoped
                // fiber so the caller's Scope does not cancel it when the handler returns.
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(unsubscribeResourceEffect(uri))).unsafe

            def serverCapabilities: Maybe[McpCapabilities.Server] =
                // Unsafe: atomic read of the handshake-populated server-capabilities ref (pure read, no scheduling).
                serverCapabilitiesRef.unsafe.get()(using AllowUnsafe.embrace.danger)

            def serverInfo: Maybe[McpInfo] =
                // Unsafe: atomic read of the handshake-populated server-info ref (pure read, no scheduling).
                serverInfoRef.unsafe.get()(using AllowUnsafe.embrace.danger)

            def protocolVersion: Maybe[McpConfig.ProtocolVersion] =
                // Unsafe: atomic read of the handshake-populated negotiated-version ref (pure read, no scheduling).
                negotiatedVersionRef.unsafe.get()(using AllowUnsafe.embrace.danger)

            def underlying: JsonRpcHandler = handler

            def close(gracePeriod: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
                // Unsafe: detach-then-reattach bridge; the inner effect runs on a fresh unscoped
                // fiber so the caller's Scope does not cancel it when the handler returns.
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(handler.close(gracePeriod))).unsafe

            private[kyo] def closeDirect(using Frame): Unit < Async =
                // Direct close: runs handler.close in-place on the caller's fiber without spawning
                // a new unsupervised fiber. Used by the Scope release slot.
                handler.close(Duration.Zero)

        end new
    end buildUnsafe

end McpClientEngine
