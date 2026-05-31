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
    final private case class ReadResourceResponse(contents: Chunk[McpResourceContents]) derives Schema

    // Wire types for prompts/get request (Decision 9).
    final private case class GetPromptRequest(name: String, arguments: Map[String, String] = Map.empty) derives Schema

    // Wire type for logging/setLevel request (Decision 10).
    final private case class SetLogLevelRequest(level: String) derives Schema
    final private case class SetLogLevelResponse() derives Schema

    // Wire types for list methods (Decision 7).
    final private case class ListRequest(cursor: Maybe[String] = Absent) derives Schema
    final private case class ToolsListResponse(tools: Chunk[McpRoute.ToolMeta], nextCursor: Maybe[String] = Absent)
        derives Schema
    final private case class ResourcesListResponse(resources: Chunk[McpRoute.ResourceMeta], nextCursor: Maybe[String] = Absent)
        derives Schema
    final private case class ResourceTemplatesListResponse(
        resourceTemplates: Chunk[McpRoute.ResourceTemplateMeta],
        nextCursor: Maybe[String] = Absent
    ) derives Schema
    final private case class PromptsListResponse(prompts: Chunk[McpRoute.PromptMeta], nextCursor: Maybe[String] = Absent)
        derives Schema

    // Wire types for completion/complete (Decision 11).
    final private case class CompleteRequest(ref: McpRoute.CompletionRef, argument: McpRoute.CompletionArg) derives Schema
    final private case class CompleteResponse(completion: McpRoute.CompletionResult) derives Schema

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
        routes: Seq[McpRoute[?, ?, ?]],
        config: McpConfig
    )(using Frame): McpClient.Unsafe < (Async & Abort[McpError | Closed]) =
        // AllowUnsafe: three AtomicRef for negotiated state shared across handler fibers.
        // Pattern mirrors McpEngine.scala AtomicRef usage (Phase 5 precedent).
        val serverCapabilitiesRef = AtomicRef.Unsafe.init[Maybe[McpCapabilities.Server]](Absent)(using AllowUnsafe.embrace.danger).safe
        val serverInfoRef         = AtomicRef.Unsafe.init[Maybe[McpInfo]](Absent)(using AllowUnsafe.embrace.danger).safe
        val negotiatedVersionRef  = AtomicRef.Unsafe.init[Maybe[McpProtocolVersion]](Absent)(using AllowUnsafe.embrace.danger).safe

        val reverseRoutes = McpReverseDispatch.buildRoutes(routes, config)

        for
            handler <- JsonRpcHandler.initUnscoped(transport, reverseRoutes, config.jsonRpc)
            initResult <- handler.call[McpInitializeRequest, McpInitializeResult](
                "initialize",
                McpInitializeRequest(McpProtocolVersion.current, clientInfo, capabilities)
            ).handle(Abort.recover[JsonRpcError] { e =>
                Abort.fail(McpHandshakeNotInitializedError(e.message))
            })
            _ <- negotiatedVersionRef.set(Present(initResult.protocolVersion))
            _ <- serverCapabilitiesRef.set(Present(initResult.capabilities))
            _ <- serverInfoRef.set(Present(initResult.serverInfo))
            _ <- handler.notify[InitializedParams]("notifications/initialized", InitializedParams())
        yield
            val unsafe = buildUnsafe(handler, serverCapabilitiesRef, serverInfoRef, negotiatedVersionRef)
            // Write the forward reference into every user route carrier so their handlers can resolve ctx.server.
            // On the client side the "server" is a no-op sentinel (user handlers must not call ctx.server from
            // client-side routes; the ref is populated to avoid an IllegalStateException during dispatch).
            // AllowUnsafe: synchronous write of forward reference immediately after client construction.
            val sentinelServer = buildClientSentinelServer(handler)
            routes.foreach { case c: McpRouteCarrier[?, ?, ?] =>
                c.serverRef.unsafe.set(Present(sentinelServer))(using AllowUnsafe.embrace.danger)
            }
            unsafe
        end for
    end initClient

    /** Builds a no-op McpServer.Unsafe sentinel for client-side route carriers.
      *
      * Client-side route handlers (sampling, elicitation, roots) receive a [[McpRoute.Context]]
      * that includes a `server` field. On the client side there is no McpServer, so we provide a
      * sentinel that aborts with a clear error if any reverse-direction method is called.
      * The handler itself must not call `ctx.server`; this sentinel exists solely to satisfy the
      * carrier's serverRef requirement and avoid IllegalStateException during dispatch.
      */
    private def buildClientSentinelServer(handler: JsonRpcHandler): McpServer.Unsafe =
        new McpServer.Unsafe:
            private def reject[A](method: String)(using Frame): A < (Async & Abort[McpError | Closed]) =
                Abort.fail(McpSamplingRejectedError(s"ctx.server.$method is not available in client-side route handlers"))

            def requestSamplingUnsafe(req: McpServer.SamplingRequest)(using
                Frame
            ): McpServer.SamplingResponse < (Async & Abort[McpError | Closed]) =
                reject("requestSampling")

            def requestRootsUnsafe(using Frame): Chunk[McpServer.Root] < (Async & Abort[McpError | Closed]) =
                reject("requestRoots")

            def requestElicitationUnsafe(req: McpServer.ElicitationRequest)(using
                Frame
            ): McpServer.ElicitationResponse < (Async & Abort[McpError | Closed]) =
                reject("requestElicitation")

            def notifyToolsListChangedUnsafe(using Frame): Unit < (Async & Abort[Closed]) =
                Sync.defer(())

            def notifyResourcesListChangedUnsafe(using Frame): Unit < (Async & Abort[Closed]) =
                Sync.defer(())

            def notifyResourceUpdatedUnsafe(uri: McpResourceUri)(using Frame): Unit < (Async & Abort[Closed]) =
                Sync.defer(())

            def notifyPromptsListChangedUnsafe(using Frame): Unit < (Async & Abort[Closed]) =
                Sync.defer(())

            def notifyLogUnsafe[T](level: McpLogLevel, data: T, logger: Maybe[String])(using
                Frame,
                Schema[T]
            ): Unit < (Async & Abort[Closed]) =
                Sync.defer(())

            def protocolVersionUnsafe: Maybe[McpProtocolVersion]        = Absent
            def clientCapabilitiesUnsafe: Maybe[McpCapabilities.Client] = Absent
            def clientInfoUnsafe: Maybe[McpInfo]                        = Absent
            def underlyingUnsafe: JsonRpcHandler                        = handler
            def awaitDrainUnsafe(using Frame): Unit < Async             = Sync.defer(())

            // The sentinel server has no real ping target; return immediately.
            def pingUnsafe(using Frame): Unit < (Async & Abort[McpError | Closed]) =
                Sync.defer(())

            def closeUnsafe(gracePeriod: Duration)(using Frame): Unit < Async =
                Sync.defer(())
        end new
    end buildClientSentinelServer

    private def buildUnsafe(
        handler: JsonRpcHandler,
        serverCapabilitiesRef: AtomicRef[Maybe[McpCapabilities.Server]],
        serverInfoRef: AtomicRef[Maybe[McpInfo]],
        negotiatedVersionRef: AtomicRef[Maybe[McpProtocolVersion]]
    ): McpClient.Unsafe =
        new McpClient.Unsafe:

            def listToolsUnsafe(cursor: Maybe[String])(using Frame): McpPage[McpRoute.ToolMeta] < (Async & Abort[McpError | Closed]) =
                handler.call[ListRequest, ToolsListResponse]("tools/list", ListRequest(cursor))
                    .map(r => McpPage(r.tools, r.nextCursor))
                    .handle(Abort.recover[JsonRpcError] { e =>
                        Abort.fail(McpInvalidArgumentError("tools/list", "response", e.message))
                    })

            def callToolUnsafe[In](name: String, arguments: In)(using
                Frame,
                Schema[In]
            ): McpRoute.ToolCallResult < (Async & Abort[McpError | Closed]) =
                val encodedArgs = Structure.encode[In](arguments)
                handler.call[ToolCallRequest, McpRoute.ToolCallResult]("tools/call", ToolCallRequest(name, encodedArgs))
                    .handle(Abort.recover[JsonRpcError] { e =>
                        Abort.fail(McpInvalidArgumentError("tools/call", "response", e.message))
                    })
            end callToolUnsafe

            def callToolTypedUnsafe[In, Out](name: String, arguments: In)(using
                Frame,
                Schema[In],
                Schema[Out]
            ): Out < (Async & Abort[McpError | Closed]) =
                callToolUnsafe[In](name, arguments).flatMap { result =>
                    result.structuredContent match
                        case Present(sv) =>
                            Structure.decode[Out](sv) match
                                case Result.Success(out) => Sync.defer(out)
                                case Result.Failure(_)   => Abort.fail(McpToolStructuredMissingError(name))
                                case Result.Panic(t)     => Abort.panic(t)
                        case Absent =>
                            Abort.fail(McpToolStructuredMissingError(name))
                }

            def listResourcesUnsafe(cursor: Maybe[String])(using
                Frame
            ): McpPage[McpRoute.ResourceMeta] < (Async & Abort[McpError | Closed]) =
                handler.call[ListRequest, ResourcesListResponse]("resources/list", ListRequest(cursor))
                    .map(r => McpPage(r.resources, r.nextCursor))
                    .handle(Abort.recover[JsonRpcError] { e =>
                        Abort.fail(McpInvalidArgumentError("resources/list", "response", e.message))
                    })

            def listResourceTemplatesUnsafe(cursor: Maybe[String])(using
                Frame
            ): McpPage[McpRoute.ResourceTemplateMeta] < (Async & Abort[McpError | Closed]) =
                handler.call[ListRequest, ResourceTemplatesListResponse]("resources/templates/list", ListRequest(cursor))
                    .map(r => McpPage(r.resourceTemplates, r.nextCursor))
                    .handle(Abort.recover[JsonRpcError] { e =>
                        Abort.fail(McpInvalidArgumentError("resources/templates/list", "response", e.message))
                    })

            def readResourceUnsafe(uri: McpResourceUri)(using Frame): Chunk[McpResourceContents] < (Async & Abort[McpError | Closed]) =
                handler.call[ReadResourceRequest, ReadResourceResponse]("resources/read", ReadResourceRequest(uri.asString))
                    .map(_.contents)
                    .handle(Abort.recover[JsonRpcError] { e =>
                        Abort.fail(McpInvalidArgumentError("resources/read", "response", e.message))
                    })

            def listPromptsUnsafe(cursor: Maybe[String])(using Frame): McpPage[McpRoute.PromptMeta] < (Async & Abort[McpError | Closed]) =
                handler.call[ListRequest, PromptsListResponse]("prompts/list", ListRequest(cursor))
                    .map(r => McpPage(r.prompts, r.nextCursor))
                    .handle(Abort.recover[JsonRpcError] { e =>
                        Abort.fail(McpInvalidArgumentError("prompts/list", "response", e.message))
                    })

            def getPromptUnsafe(name: String, arguments: Map[String, String])(using
                Frame
            ): McpRoute.PromptGetResult < (Async & Abort[McpError | Closed]) =
                handler.call[GetPromptRequest, McpRoute.PromptGetResult]("prompts/get", GetPromptRequest(name, arguments))
                    .handle(Abort.recover[JsonRpcError] { e =>
                        Abort.fail(McpInvalidArgumentError("prompts/get", "response", e.message))
                    })

            def setLogLevelUnsafe(level: McpLogLevel)(using Frame): Unit < (Async & Abort[McpError | Closed]) =
                handler.call[SetLogLevelRequest, SetLogLevelResponse]("logging/setLevel", SetLogLevelRequest(level.toString.toLowerCase))
                    .map(_ => ())
                    .handle(Abort.recover[JsonRpcError] { e =>
                        Abort.fail(McpInvalidArgumentError("logging/setLevel", "response", e.message))
                    })

            def completeUnsafe(ref: McpRoute.CompletionRef, arg: McpRoute.CompletionArg)(using
                Frame
            ): McpRoute.CompletionResult < (Async & Abort[McpError | Closed]) =
                handler.call[CompleteRequest, CompleteResponse]("completion/complete", CompleteRequest(ref, arg))
                    .map(_.completion)
                    .handle(Abort.recover[JsonRpcError] { e =>
                        Abort.fail(McpInvalidArgumentError("completion/complete", "response", e.message))
                    })

            def notifyRootsListChangedUnsafe(using Frame): Unit < (Async & Abort[Closed]) =
                handler.notify[NotifyEmptyParams]("notifications/roots/list_changed", NotifyEmptyParams())

            def pingUnsafe(using Frame): Unit < (Async & Abort[McpError | Closed]) =
                handler.call[NotifyEmptyParams, NotifyEmptyParams]("ping", NotifyEmptyParams())
                    .map(_ => ())
                    .handle(Abort.recover[JsonRpcError] { e =>
                        Abort.fail(McpInvalidArgumentError("ping", "response", e.message))
                    })

            def serverCapabilitiesUnsafe: Maybe[McpCapabilities.Server] =
                // AllowUnsafe: synchronous read of post-handshake negotiated state; populated before instance is returned.
                serverCapabilitiesRef.unsafe.get()(using AllowUnsafe.embrace.danger)

            def serverInfoUnsafe: Maybe[McpInfo] =
                // AllowUnsafe: synchronous read of post-handshake negotiated state; populated before instance is returned.
                serverInfoRef.unsafe.get()(using AllowUnsafe.embrace.danger)

            def protocolVersionUnsafe: Maybe[McpProtocolVersion] =
                // AllowUnsafe: synchronous read of post-handshake negotiated state; populated before instance is returned.
                negotiatedVersionRef.unsafe.get()(using AllowUnsafe.embrace.danger)

            def underlyingUnsafe: JsonRpcHandler = handler

            def closeUnsafe(gracePeriod: Duration)(using Frame): Unit < Async =
                handler.close(gracePeriod)

        end new
    end buildUnsafe

end McpClientEngine
