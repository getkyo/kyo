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
  * populated before the instance is yielded (Decision 1, prep.md edge-case 1).
  */
private[kyo] object McpClientEngine:

    // Wire type for the notifications/initialized outbound notification.
    // Empty case class encodes as {} per kyo-schema, matching MCP spec. (Decision 4)
    final private case class InitializedParams() derives Schema

    // Wire type for client-to-server outbound notifications that carry no params.
    final private case class NotifyEmptyParams() derives Schema

    // Wire types for tools/call request (Decision 5).
    final private case class ToolCallRequest(name: String, arguments: Structure.Value) derives Schema

    // Wire types for resources/read request (Decision 8).
    final private case class ReadResourceRequest(uri: String) derives Schema
    final private case class ReadResourceResponse(contents: Chunk[McpHandler.ResourceContents]) derives Schema

    // Wire types for prompts/get request (Decision 9).
    final private case class GetPromptRequest(name: String, arguments: Map[String, String] = Map.empty) derives Schema

    // Wire type for logging/setLevel request (Decision 10).
    final private case class SetLogLevelRequest(level: String) derives Schema
    final private case class SetLogLevelResponse() derives Schema

    // Wire types for list methods (Decision 7).
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

    // Wire types for completion/complete (Decision 11).
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
        handlers: Seq[McpHandler[?, ?, ?]],
        config: McpConfig
    )(using Frame): McpClient.Unsafe < (Async & Abort[McpException | Closed]) =
        // AllowUnsafe: three AtomicRef for negotiated state shared across handler fibers.
        // Pattern mirrors McpEngine.scala AtomicRef usage (Phase 5 precedent).
        val serverCapabilitiesRef = AtomicRef.Unsafe.init[Maybe[McpCapabilities.Server]](Absent)(using AllowUnsafe.embrace.danger).safe
        val serverInfoRef         = AtomicRef.Unsafe.init[Maybe[McpInfo]](Absent)(using AllowUnsafe.embrace.danger).safe
        val negotiatedVersionRef  = AtomicRef.Unsafe.init[Maybe[McpConfig.ProtocolVersion]](Absent)(using AllowUnsafe.embrace.danger).safe
        // AllowUnsafe: forward reference holding the sentinel "server" used to bind into Mcp.local
        // during client-side reverse-direction dispatch.
        val serverRef = AtomicRef.Unsafe.init[Maybe[McpServer.Unsafe]](Absent)(using AllowUnsafe.embrace.danger).safe

        val reverseRoutes = McpReverseDispatch.buildRoutes(handlers, config, capabilities, serverRef)

        for
            handler <- JsonRpcHandler.initUnscoped(transport, reverseRoutes, config.jsonRpc)
            initResult <- handler.call[McpInitializeRequest, McpInitializeResult](
                "initialize",
                McpInitializeRequest(McpConfig.ProtocolVersion.current, clientInfo, capabilities)
            ).handle(Abort.recover[JsonRpcError] { e =>
                Abort.fail(McpHandshakeNotInitializedException(e.message))
            })
            _ <- negotiatedVersionRef.set(Present(initResult.protocolVersion))
            _ <- serverCapabilitiesRef.set(Present(initResult.capabilities))
            _ <- serverInfoRef.set(Present(initResult.serverInfo))
            _ <- handler.notify[InitializedParams]("notifications/initialized", InitializedParams())
        yield
            val unsafe = buildUnsafe(handler, serverCapabilitiesRef, serverInfoRef, negotiatedVersionRef)
            // Publish the sentinel server (which rejects reverse-direction calls) so that client-side
            // reverse-direction handlers can still bind a context into Mcp.local without panic.
            // AllowUnsafe: synchronous write of forward reference immediately after construction.
            val sentinelServer = buildClientSentinelServer(handler)
            serverRef.unsafe.set(Present(sentinelServer))(using AllowUnsafe.embrace.danger)
            unsafe
        end for
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
            private def reject[A](method: String)(using Frame): A < (Async & Abort[McpException | Closed]) =
                Abort.fail(McpSamplingRejectedException(s"ctx.server.$method is not available in client-side route handlers"))

            def requestSampling(req: McpServer.SamplingRequest)(using
                AllowUnsafe,
                Frame
            ): Fiber.Unsafe[McpServer.SamplingResponse, Abort[McpException | Closed]] =
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(reject[McpServer.SamplingResponse]("requestSampling"))).unsafe

            def requestRoots(using AllowUnsafe, Frame): Fiber.Unsafe[Chunk[McpServer.Root], Abort[McpException | Closed]] =
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(reject[Chunk[McpServer.Root]]("requestRoots"))).unsafe

            def requestElicitation(req: McpServer.ElicitationRequest)(using
                AllowUnsafe,
                Frame
            ): Fiber.Unsafe[McpServer.ElicitationResponse, Abort[McpException | Closed]] =
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(reject[McpServer.ElicitationResponse]("requestElicitation"))).unsafe

            def notifyToolsListChanged(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]] =
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(Sync.defer[Unit, Abort[Closed]](()))).unsafe

            def notifyResourcesListChanged(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]] =
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(Sync.defer[Unit, Abort[Closed]](()))).unsafe

            def notifyResourceUpdated(uri: McpResourceUri)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]] =
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(Sync.defer[Unit, Abort[Closed]](()))).unsafe

            def notifyPromptsListChanged(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]] =
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(Sync.defer[Unit, Abort[Closed]](()))).unsafe

            def notifyLog[T](level: McpServer.LogLevel, data: T, logger: Maybe[String])(using
                AllowUnsafe,
                Frame,
                Schema[T]
            ): Fiber.Unsafe[Unit, Abort[Closed]] =
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(Sync.defer[Unit, Abort[Closed]](()))).unsafe

            def protocolVersion: Maybe[McpConfig.ProtocolVersion] = Absent
            def clientCapabilities: Maybe[McpCapabilities.Client] = Absent
            def clientInfo: Maybe[McpInfo]                        = Absent
            def underlying: JsonRpcHandler                        = handler

            def awaitDrain(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(Sync.defer(()))).unsafe

            def close(gracePeriod: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(Sync.defer(()))).unsafe
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

            private def listToolsEffect(cursor: Maybe[String])(using
                Frame
            ): McpClient.Page[McpHandler.ToolMeta] < (Async & Abort[McpException | Closed]) =
                handler.call[ListRequest, ToolsListResponse]("tools/list", ListRequest(cursor))
                    .map(r => McpClient.Page(r.tools, r.nextCursor))
                    .handle(Abort.recover[JsonRpcError] { e =>
                        Abort.fail(McpRemoteApplicationException(e.code, e.message, e.data))
                    })

            private def callToolEffect[In](name: String, arguments: In)(using
                Frame,
                Schema[In]
            ): McpHandler.ToolOutcome < (Async & Abort[McpException | Closed]) =
                val encodedArgs = Structure.encode[In](arguments)
                handler.call[ToolCallRequest, McpHandler.ToolOutcome]("tools/call", ToolCallRequest(name, encodedArgs))
                    .handle(Abort.recover[JsonRpcError] { e =>
                        Abort.fail(McpRemoteApplicationException(e.code, e.message, e.data))
                    })
            end callToolEffect

            private def callToolTypedEffect[In, Out](name: String, arguments: In)(using
                Frame,
                Schema[In],
                Schema[Out]
            ): Out < (Async & Abort[McpException | Closed]) =
                callToolEffect[In](name, arguments).flatMap { result =>
                    result.structuredContent match
                        case Present(sv) =>
                            Structure.decode[Out](sv) match
                                case Result.Success(out) => Sync.defer(out)
                                case Result.Failure(_)   => Abort.fail(McpToolStructuredMissingException(name))
                                case Result.Panic(t)     => Abort.panic(t)
                        case Absent =>
                            Abort.fail(McpToolStructuredMissingException(name))
                }

            private def listResourcesEffect(cursor: Maybe[String])(using
                Frame
            ): McpClient.Page[McpHandler.ResourceMeta] < (Async & Abort[McpException | Closed]) =
                handler.call[ListRequest, ResourcesListResponse]("resources/list", ListRequest(cursor))
                    .map(r => McpClient.Page(r.resources, r.nextCursor))
                    .handle(Abort.recover[JsonRpcError] { e =>
                        Abort.fail(McpRemoteApplicationException(e.code, e.message, e.data))
                    })

            private def listResourceTemplatesEffect(cursor: Maybe[String])(using
                Frame
            ): McpClient.Page[McpHandler.ResourceTemplateMeta] < (Async & Abort[McpException | Closed]) =
                handler.call[ListRequest, ResourceTemplatesListResponse]("resources/templates/list", ListRequest(cursor))
                    .map(r => McpClient.Page(r.resourceTemplates, r.nextCursor))
                    .handle(Abort.recover[JsonRpcError] { e =>
                        Abort.fail(McpRemoteApplicationException(e.code, e.message, e.data))
                    })

            private def readResourceEffect(uri: McpResourceUri)(using
                Frame
            ): Chunk[McpHandler.ResourceContents] < (Async & Abort[McpException | Closed]) =
                handler.call[ReadResourceRequest, ReadResourceResponse]("resources/read", ReadResourceRequest(uri.asString))
                    .map(_.contents)
                    .handle(Abort.recover[JsonRpcError] { e =>
                        Abort.fail(McpRemoteApplicationException(e.code, e.message, e.data))
                    })

            private def listPromptsEffect(cursor: Maybe[String])(using
                Frame
            ): McpClient.Page[McpHandler.PromptMeta] < (Async & Abort[McpException | Closed]) =
                handler.call[ListRequest, PromptsListResponse]("prompts/list", ListRequest(cursor))
                    .map(r => McpClient.Page(r.prompts, r.nextCursor))
                    .handle(Abort.recover[JsonRpcError] { e =>
                        Abort.fail(McpRemoteApplicationException(e.code, e.message, e.data))
                    })

            private def getPromptEffect(name: String, arguments: Map[String, String])(using
                Frame
            ): McpHandler.PromptOutcome < (Async & Abort[McpException | Closed]) =
                handler.call[GetPromptRequest, McpHandler.PromptOutcome]("prompts/get", GetPromptRequest(name, arguments))
                    .handle(Abort.recover[JsonRpcError] { e =>
                        Abort.fail(McpRemoteApplicationException(e.code, e.message, e.data))
                    })

            private def setLogLevelEffect(level: McpServer.LogLevel)(using Frame): Unit < (Async & Abort[McpException | Closed]) =
                handler.call[SetLogLevelRequest, SetLogLevelResponse]("logging/setLevel", SetLogLevelRequest(level.toString.toLowerCase))
                    .map(_ => ())
                    .handle(Abort.recover[JsonRpcError] { e =>
                        Abort.fail(McpRemoteApplicationException(e.code, e.message, e.data))
                    })

            private def completeEffect(
                ref: McpHandler.CompletionRef,
                arg: McpHandler.CompletionArg,
                context: Maybe[McpHandler.CompletionArg.Context]
            )(using
                Frame
            ): McpHandler.CompletionOutcome < (Async & Abort[McpException | Closed]) =
                handler.call[CompleteRequestWithContext, CompleteResponse](
                    "completion/complete",
                    CompleteRequestWithContext(ref, arg, context)
                )
                    .map(_.completion)
                    .handle(Abort.recover[JsonRpcError] { e =>
                        Abort.fail(McpRemoteApplicationException(e.code, e.message, e.data))
                    })

            private def notifyRootsListChangedEffect(using Frame): Unit < (Async & Abort[Closed]) =
                handler.notify[NotifyEmptyParams]("notifications/roots/list_changed", NotifyEmptyParams())

            private def pingEffect(using Frame): Unit < (Async & Abort[McpException | Closed]) =
                handler.call[NotifyEmptyParams, NotifyEmptyParams]("ping", NotifyEmptyParams())
                    .map(_ => ())
                    .handle(Abort.recover[JsonRpcError] { e =>
                        Abort.fail(McpRemoteApplicationException(e.code, e.message, e.data))
                    })

            private def subscribeResourceEffect(uri: McpResourceUri)(using Frame): Unit < (Async & Abort[McpException | Closed]) =
                handler.call[SubscribeRequest, SubscribeResponse]("resources/subscribe", SubscribeRequest(uri.asString))
                    .map(_ => ())
                    .handle(Abort.recover[JsonRpcError] { e =>
                        Abort.fail(McpRemoteApplicationException(e.code, e.message, e.data))
                    })

            private def unsubscribeResourceEffect(uri: McpResourceUri)(using Frame): Unit < (Async & Abort[McpException | Closed]) =
                handler.call[SubscribeRequest, SubscribeResponse]("resources/unsubscribe", SubscribeRequest(uri.asString))
                    .map(_ => ())
                    .handle(Abort.recover[JsonRpcError] { e =>
                        Abort.fail(McpRemoteApplicationException(e.code, e.message, e.data))
                    })

            // --- Public Unsafe interface ---

            def listTools(cursor: Maybe[String])(using
                AllowUnsafe,
                Frame
            ): Fiber.Unsafe[McpClient.Page[McpHandler.ToolMeta], Abort[McpException | Closed]] =
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(listToolsEffect(cursor))).unsafe

            def callTool[In](name: String, arguments: In)(using
                AllowUnsafe,
                Frame,
                Schema[In]
            ): Fiber.Unsafe[McpHandler.ToolOutcome, Abort[McpException | Closed]] =
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(callToolEffect[In](name, arguments))).unsafe

            def callToolTyped[In, Out](name: String, arguments: In)(using
                AllowUnsafe,
                Frame,
                Schema[In],
                Schema[Out]
            ): Fiber.Unsafe[Out, Abort[McpException | Closed]] =
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(callToolTypedEffect[In, Out](name, arguments))).unsafe

            def listResources(cursor: Maybe[String])(using
                AllowUnsafe,
                Frame
            ): Fiber.Unsafe[McpClient.Page[McpHandler.ResourceMeta], Abort[McpException | Closed]] =
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(listResourcesEffect(cursor))).unsafe

            def listResourceTemplates(cursor: Maybe[String])(using
                AllowUnsafe,
                Frame
            ): Fiber.Unsafe[McpClient.Page[McpHandler.ResourceTemplateMeta], Abort[McpException | Closed]] =
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(listResourceTemplatesEffect(cursor))).unsafe

            def readResource(uri: McpResourceUri)(using
                AllowUnsafe,
                Frame
            ): Fiber.Unsafe[Chunk[McpHandler.ResourceContents], Abort[McpException | Closed]] =
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(readResourceEffect(uri))).unsafe

            def listPrompts(cursor: Maybe[String])(using
                AllowUnsafe,
                Frame
            ): Fiber.Unsafe[McpClient.Page[McpHandler.PromptMeta], Abort[McpException | Closed]] =
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(listPromptsEffect(cursor))).unsafe

            def getPrompt(name: String, arguments: Map[String, String])(using
                AllowUnsafe,
                Frame
            ): Fiber.Unsafe[McpHandler.PromptOutcome, Abort[McpException | Closed]] =
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(getPromptEffect(name, arguments))).unsafe

            def setLogLevel(level: McpServer.LogLevel)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[McpException | Closed]] =
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(setLogLevelEffect(level))).unsafe

            def complete(
                ref: McpHandler.CompletionRef,
                arg: McpHandler.CompletionArg,
                context: Maybe[McpHandler.CompletionArg.Context]
            )(using AllowUnsafe, Frame): Fiber.Unsafe[McpHandler.CompletionOutcome, Abort[McpException | Closed]] =
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(completeEffect(ref, arg, context))).unsafe

            def notifyRootsListChanged(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]] =
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(notifyRootsListChangedEffect)).unsafe

            def ping(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[McpException | Closed]] =
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(pingEffect)).unsafe

            def subscribeResource(uri: McpResourceUri)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[McpException | Closed]] =
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(subscribeResourceEffect(uri))).unsafe

            def unsubscribeResource(uri: McpResourceUri)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[McpException | Closed]] =
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(unsubscribeResourceEffect(uri))).unsafe

            def serverCapabilities: Maybe[McpCapabilities.Server] =
                serverCapabilitiesRef.unsafe.get()(using AllowUnsafe.embrace.danger)

            def serverInfo: Maybe[McpInfo] =
                serverInfoRef.unsafe.get()(using AllowUnsafe.embrace.danger)

            def protocolVersion: Maybe[McpConfig.ProtocolVersion] =
                negotiatedVersionRef.unsafe.get()(using AllowUnsafe.embrace.danger)

            def underlying: JsonRpcHandler = handler

            def close(gracePeriod: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
                Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(handler.close(gracePeriod))).unsafe

        end new
    end buildUnsafe

end McpClientEngine
