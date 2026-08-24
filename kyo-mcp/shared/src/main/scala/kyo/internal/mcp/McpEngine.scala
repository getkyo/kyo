package kyo.internal.mcp

import kyo.*

/** Composes all MCP engine components into a live `McpServer.Unsafe` instance.
  *
  * Wiring order:
  *   1. Build `McpCatalog` from user handlers.
  *   2. Auto-derive or use declared `McpCapabilities.Server`.
  *   3. Build `McpHandshakeGate` and `McpCapabilityGate`; compose them.
  *   4. Build engine-owned routes (initialize, builtins).
  *   5. Lift user `McpHandler` instances to `JsonRpcRoute` via their `underlying` field,
  *      wrapping each dispatch in `Mcp.local.let(Present(ctx))` so route handlers can reach
  *      the per-request context through the `Mcp.*` accessors.
  *   6. Call `JsonRpcHandler.initUnscoped` with all routes.
  *   7. Return the concrete `McpServer.Unsafe` anonymous class.
  */
private[kyo] object McpEngine:

    // Wire shapes for reverse-direction calls.
    final private case class NotifyEmptyParams() derives Schema
    final private case class ResourceUpdatedParams(uri: String) derives Schema
    final private case class LogMessageParams(level: String, data: Structure.Value, logger: Maybe[String] = Absent) derives Schema

    def initServer(
        transport: JsonRpcTransport,
        userHandlers: Seq[McpHandler[?, ?, ?]],
        config: McpConfig
    )(using Frame): McpServer.Unsafe < Async =
        // Unsafe: AtomicRef for post-handshake state shared across handler fibers.
        val negotiatedVersionRef  = AtomicRef.Unsafe.init[Maybe[McpConfig.ProtocolVersion]](Absent)(using AllowUnsafe.embrace.danger).safe
        val clientCapabilitiesRef = AtomicRef.Unsafe.init[Maybe[McpCapabilities.Client]](Absent)(using AllowUnsafe.embrace.danger).safe
        val clientInfoRef         = AtomicRef.Unsafe.init[Maybe[McpInfo]](Absent)(using AllowUnsafe.embrace.danger).safe
        // Unsafe: AtomicRef for log level threshold; initialized to Info per §3.9.
        val logLevelRef = AtomicRef.Unsafe.init[McpServer.LogLevel](McpServer.LogLevel.Info)(using AllowUnsafe.embrace.danger).safe
        // Unsafe: AtomicRef for resource subscription set; initialized to empty per §3.4.
        val subscriptionsRef = AtomicRef.Unsafe.init[Set[McpResourceUri]](Set.empty)(using AllowUnsafe.embrace.danger).safe
        // Forward reference holding the live McpServer.Unsafe so each dispatch can bind it into
        // Mcp.local. A promise rather than a slot: it is completed right after JsonRpcHandler.initUnscoped
        // returns, but that call has already started the dispatch loop, so a request arriving in between
        // must WAIT for the server rather than find it missing. Unsafe: constructed outside an effect,
        // like the sibling refs above.
        val serverRef =
            Fiber.Promise.Unsafe.init[McpServer.Unsafe, Any]()(using AllowUnsafe.embrace.danger).safe

        val catalog = McpCatalog(userHandlers)

        // MCP reserves error codes -32003 to -32000 for framework use (design §5.1). A handler that
        // registers a mapping in that range is a handler-authoring programmer error, thrown as a typed
        // McpConfigurationError (panic) before the transport opens, consistent with McpConfig.require and
        // the sibling JsonRpcHandler.Config.require.
        userHandlers.foreach { h =>
            h.errorMappings.foreach { m =>
                if m.code >= -32003 && m.code <= -32000 then
                    throw McpConfigurationError(
                        s"handler '${h.name}'.error",
                        s"error code ${m.code} falls in the framework-reserved range [-32003, -32000]; use codes outside that range"
                    )
            }
        }

        // A handler whose body aborts a type no `.error[E2]` covers cannot answer that failure: the
        // engine reaches its unmapped-error path and the peer gets a -32603 carrying the exception's
        // `toString`, which leaks whatever the exception carries (a query, a connection id) inside a code
        // the model cannot discriminate. The information to refuse it is available here, so the handler
        // is rejected before the transport opens, the same class of programmer error as the reserved-code
        // check above and reported the same way.
        userHandlers.foreach { h =>
            h.declaredErrorTag.foreach { declared =>
                errorLeaves(declared).foreach { leaf =>
                    // A leaf that is already a JsonRpcError carries its own code and message, and the
                    // dispatch boundary forwards it as-is rather than taking the unmapped path. Requiring
                    // a mapping for it would refuse the handlers that abort `McpToolExecutionException`
                    // and friends, which are exactly the ones that already answer correctly. This mirrors
                    // the runtime branch in `JsonRpcRoute.applyMappingsAtBoundary`.
                    val wireShaped = leaf <:< jsonRpcErrorTag
                    if !wireShaped && !h.errorMappings.exists(m => leaf <:< m.tag.asInstanceOf[ConcreteTag[Any]]) then
                        throw McpConfigurationError(
                            s"handler '${h.name}'",
                            s"the handler body can abort ${leaf.showType}, which no `.error[...]` maps to a wire code; " +
                                s"register a mapping for it, or handle it inside the body"
                        )
                    end if
                }
            }
        }

        val serverCaps = catalog.autoDeriveServerCapabilities(config)

        val handshakeGate  = McpHandshakeGate.server(config.handshakeOrder)
        val capabilityGate = McpCapabilityGate.server(serverCaps, config.capabilityGate)

        val composedGate: JsonRpcMessageGate = new JsonRpcMessageGate:
            def beforeDispatch(env: JsonRpcEnvelope)(using Frame): JsonRpcMessageGate.Decision < Sync =
                handshakeGate.beforeDispatch(env).map {
                    case JsonRpcMessageGate.Decision.Allow => capabilityGate.beforeDispatch(env)
                    case other                             => other
                }

        val jsonRpcConfig = config.jsonRpc.gate(composedGate)

        val initializeRoute = McpInitializeRoute.build(
            config,
            serverCaps,
            negotiatedVersionRef,
            clientCapabilitiesRef,
            clientInfoRef
        )

        val builtinRoutes: Seq[JsonRpcRoute[?, ?, ?]] = Seq(
            McpBuiltInRoutes.toolsList(catalog),
            McpBuiltInRoutes.toolsCall(catalog, serverRef),
            McpBuiltInRoutes.resourcesList(catalog),
            McpBuiltInRoutes.resourcesRead(catalog, serverRef),
            McpBuiltInRoutes.resourceTemplatesList(catalog),
            McpBuiltInRoutes.promptsList(catalog),
            McpBuiltInRoutes.promptsGet(catalog, serverRef),
            McpBuiltInRoutes.completionComplete(catalog, serverRef)
        ) ++ (if serverCaps.logging.isDefined then Seq(McpBuiltInRoutes.loggingSetLevel(logLevelRef)) else Seq.empty)
            ++ (if serverCaps.resources.exists(_.subscribe) then
                    Seq(
                        McpBuiltInRoutes.resourcesSubscribe(subscriptionsRef),
                        McpBuiltInRoutes.resourcesUnsubscribe(subscriptionsRef)
                    )
                else Seq.empty)

        // Register no-op handlers for MCP-protocol notifications that are not user routes.
        // The strict unknown-method policy would reject them; these stubs accept and discard them.
        // The handshake gate already processes notifications/initialized via beforeDispatch.
        val initializedNotifRoute: JsonRpcRoute[?, ?, ?] =
            JsonRpcRoute.notification[NotifyEmptyParams]("notifications/initialized") { (_, _) => () }
        val rootsListChangedRoute: JsonRpcRoute[?, ?, ?] =
            JsonRpcRoute.notification[NotifyEmptyParams]("notifications/roots/list_changed") { (_, _) =>
                // The client advertises its roots list changed; the spec defines no required server
                // reaction beyond optionally re-listing, so this notification is accepted and discarded.
                ()
            }

        // MCP 2025-06-18 §3.8: ping must be handled by both client and server.
        // Responds with an empty object to confirm liveness. Registered unconditionally.
        val pingRoute: JsonRpcRoute[?, ?, ?] =
            JsonRpcRoute.request[NotifyEmptyParams, NotifyEmptyParams]("ping") { (_, _) => NotifyEmptyParams() }

        // Lift user McpHandler carriers to JsonRpcRoute, wrapping each dispatch in Mcp.local.let
        // so the handler closure can reach the per-request context through Mcp.*.
        val userJsonRpcRoutes: Seq[JsonRpcRoute[?, ?, ?]] = userHandlers.map(h => McpHandlerLift.lift(h, serverRef))
        val allRoutes: Seq[JsonRpcRoute[?, ?, ?]] =
            Seq(initializeRoute, initializedNotifRoute, rootsListChangedRoute, pingRoute) ++ builtinRoutes ++ userJsonRpcRoutes

        // Config and reserved-error-code validation already ran (throwing); build and publish the live server.
        JsonRpcHandler.initUnscoped(transport, allRoutes, jsonRpcConfig).map { handler =>
            val unsafe: McpServer.Unsafe = new McpServer.Unsafe:

                // Maps the residual transport `Closed` (the member left after the JsonRpcError recover)
                // to the typed McpConnectionClosedException so no bare `Closed` survives onto a public row.
                private def recoverClosed[A, S](v: A < (S & Abort[Closed]))(using
                    Frame
                ): A < (S & Abort[McpConnectionClosedException]) =
                    Abort.recover[Closed](_ => Abort.fail(McpConnectionClosedException()))(v)

                private def requestSamplingEffect(request: McpServer.SamplingRequest)(using
                    Frame
                ): McpServer.SamplingResponse < (Async & Abort[McpRequestSamplingFailure]) =
                    recoverClosed(
                        handler.call[McpServer.SamplingRequest, McpServer.SamplingResponse]("sampling/createMessage", request)
                            .handle(Abort.recover[JsonRpcError] { e =>
                                if e.code == -32601 then
                                    Abort.fail(McpCapabilityNotAdvertisedException(
                                        "sampling/createMessage",
                                        McpCapabilities.Name.Sampling,
                                        McpCapabilityNotAdvertisedException.Peer.Client
                                    ))
                                else Abort.fail(McpSamplingRejectedException(e.message))
                            })
                    )

                private def requestRootsEffect(using Frame): Chunk[McpServer.Root] < (Async & Abort[McpRequestRootsFailure]) =
                    recoverClosed(
                        handler.call[NotifyEmptyParams, McpRootsListResponse]("roots/list", NotifyEmptyParams())
                            .map(_.roots)
                            .handle(Abort.recover[JsonRpcError] { e =>
                                if e.code == -32601 then
                                    Abort.fail(McpCapabilityNotAdvertisedException(
                                        "roots/list",
                                        McpCapabilities.Name.Roots,
                                        McpCapabilityNotAdvertisedException.Peer.Client
                                    ))
                                else Abort.fail(McpInvalidArgumentException("roots/list", "response", e.message))
                            })
                    )

                private def requestElicitationEffect(request: McpServer.ElicitationRequest)(using
                    Frame
                ): McpServer.ElicitationResponse < (Async & Abort[McpRequestElicitationFailure]) =
                    recoverClosed(
                        handler.call[McpServer.ElicitationRequest, McpServer.ElicitationResponse]("elicitation/create", request)
                            .handle(Abort.recover[JsonRpcError] { e =>
                                Abort.fail(McpElicitationDeclinedException(e.message))
                            })
                    )

                private def notifyToolsListChangedEffect(using Frame): Unit < (Async & Abort[McpConnectionClosedException]) =
                    recoverClosed(
                        serverCaps.tools match
                            case Present(tc) if tc.listChanged =>
                                handler.notify[NotifyEmptyParams]("notifications/tools/list_changed", NotifyEmptyParams())
                            case _ =>
                                Sync.defer(())
                    )

                private def notifyResourcesListChangedEffect(using Frame): Unit < (Async & Abort[McpConnectionClosedException]) =
                    recoverClosed(
                        serverCaps.resources match
                            case Present(rc) if rc.listChanged =>
                                handler.notify[NotifyEmptyParams]("notifications/resources/list_changed", NotifyEmptyParams())
                            case _ =>
                                Sync.defer(())
                    )

                private def notifyResourceUpdatedEffect(uri: McpResourceUri)(using
                    Frame
                ): Unit < (Async & Abort[McpConnectionClosedException]) =
                    recoverClosed(
                        serverCaps.resources match
                            case Present(rc) if rc.subscribe =>
                                subscriptionsRef.get.flatMap { subs =>
                                    if subs.contains(uri) then
                                        handler.notify[ResourceUpdatedParams](
                                            "notifications/resources/updated",
                                            ResourceUpdatedParams(uri.asString)
                                        )
                                    else Sync.defer(())
                                }
                            case Present(_) =>
                                // subscribe = false (or no active subscriber): a no-op, never an unconditional emit.
                                Sync.defer(())
                            case Absent =>
                                Sync.defer(())
                    )

                private def notifyPromptsListChangedEffect(using Frame): Unit < (Async & Abort[McpConnectionClosedException]) =
                    recoverClosed(
                        serverCaps.prompts match
                            case Present(pc) if pc.listChanged =>
                                handler.notify[NotifyEmptyParams]("notifications/prompts/list_changed", NotifyEmptyParams())
                            case _ =>
                                Sync.defer(())
                    )

                private def notifyLogEffect[T](level: McpServer.LogLevel, data: T, logger: Maybe[String])(using
                    Frame,
                    Schema[T]
                ): Unit < (Async & Abort[McpConnectionClosedException]) =
                    recoverClosed(
                        logLevelRef.get.flatMap { threshold =>
                            if level.ordinal >= threshold.ordinal then
                                val encoded = Structure.encode[T](data)
                                handler.notify[LogMessageParams](
                                    "notifications/message",
                                    LogMessageParams(
                                        level = level.toString.toLowerCase,
                                        data = encoded,
                                        logger = logger
                                    )
                                )
                            else Sync.defer(())
                        }
                    )
                end notifyLogEffect

                // --- Public Unsafe interface ---

                def requestSampling(request: McpServer.SamplingRequest)(using
                    AllowUnsafe,
                    Frame
                ): Fiber.Unsafe[McpServer.SamplingResponse, Abort[McpRequestSamplingFailure]] =
                    // Unsafe: detach-then-reattach bridge; the inner effect runs on a fresh unscoped
                    // fiber so the caller's Scope does not cancel it when the handler returns.
                    Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(requestSamplingEffect(request))).unsafe

                def requestRoots(using AllowUnsafe, Frame): Fiber.Unsafe[Chunk[McpServer.Root], Abort[McpRequestRootsFailure]] =
                    // Unsafe: detach-then-reattach bridge; the inner effect runs on a fresh unscoped
                    // fiber so the caller's Scope does not cancel it when the handler returns.
                    Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(requestRootsEffect)).unsafe

                def requestElicitation(request: McpServer.ElicitationRequest)(using
                    AllowUnsafe,
                    Frame
                ): Fiber.Unsafe[McpServer.ElicitationResponse, Abort[McpRequestElicitationFailure]] =
                    // Unsafe: detach-then-reattach bridge; the inner effect runs on a fresh unscoped
                    // fiber so the caller's Scope does not cancel it when the handler returns.
                    Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(requestElicitationEffect(request))).unsafe

                def notifyToolsListChanged(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[McpConnectionClosedException]] =
                    // Unsafe: detach-then-reattach bridge; the inner effect runs on a fresh unscoped
                    // fiber so the caller's Scope does not cancel it when the handler returns.
                    Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(notifyToolsListChangedEffect)).unsafe

                def notifyResourcesListChanged(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[McpConnectionClosedException]] =
                    // Unsafe: detach-then-reattach bridge; the inner effect runs on a fresh unscoped
                    // fiber so the caller's Scope does not cancel it when the handler returns.
                    Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(notifyResourcesListChangedEffect)).unsafe

                def notifyResourceUpdated(uri: McpResourceUri)(using
                    AllowUnsafe,
                    Frame
                ): Fiber.Unsafe[Unit, Abort[McpConnectionClosedException]] =
                    // Unsafe: detach-then-reattach bridge; the inner effect runs on a fresh unscoped
                    // fiber so the caller's Scope does not cancel it when the handler returns.
                    Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(notifyResourceUpdatedEffect(uri))).unsafe

                def notifyPromptsListChanged(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[McpConnectionClosedException]] =
                    // Unsafe: detach-then-reattach bridge; the inner effect runs on a fresh unscoped
                    // fiber so the caller's Scope does not cancel it when the handler returns.
                    Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(notifyPromptsListChangedEffect)).unsafe

                def notifyLog[T](level: McpServer.LogLevel, data: T, logger: Maybe[String])(using
                    AllowUnsafe,
                    Frame,
                    Schema[T]
                ): Fiber.Unsafe[Unit, Abort[McpConnectionClosedException]] =
                    // Unsafe: detach-then-reattach bridge; the inner effect runs on a fresh unscoped
                    // fiber so the caller's Scope does not cancel it when the handler returns.
                    Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(notifyLogEffect[T](level, data, logger))).unsafe

                def protocolVersion: Maybe[McpConfig.ProtocolVersion] =
                    // Unsafe: atomic read of the handshake-populated negotiated-version ref (pure read, no scheduling).
                    negotiatedVersionRef.unsafe.get()(using AllowUnsafe.embrace.danger)

                def clientCapabilities: Maybe[McpCapabilities.Client] =
                    // Unsafe: atomic read of the handshake-populated client-capabilities ref (pure read, no scheduling).
                    clientCapabilitiesRef.unsafe.get()(using AllowUnsafe.embrace.danger)

                def clientInfo: Maybe[McpInfo] =
                    // Unsafe: atomic read of the handshake-populated client-info ref (pure read, no scheduling).
                    clientInfoRef.unsafe.get()(using AllowUnsafe.embrace.danger)

                def underlying: JsonRpcHandler = handler

                def awaitDrain(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
                    // Unsafe: detach-then-reattach bridge; the inner effect runs on a fresh unscoped
                    // fiber so the caller's Scope does not cancel it when the handler returns.
                    Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(handler.awaitDrain)).unsafe

                def close(gracePeriod: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
                    // Unsafe: detach-then-reattach bridge; the inner effect runs on a fresh unscoped
                    // fiber so the caller's Scope does not cancel it when the handler returns.
                    Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(handler.close(gracePeriod))).unsafe

                private[kyo] def closeDirect(using Frame): Unit < Async =
                    // Direct close: runs handler.close in-place on the caller's fiber without spawning
                    // a new unsupervised fiber. Used by the Scope release slot.
                    handler.close(Duration.Zero)

            end unsafe

            // Publish the live server, releasing any dispatch already waiting on it. Unsafe: synchronous
            // completion of the forward reference immediately after construction.
            serverRef.unsafe.completeDiscard(Result.succeed(unsafe))(using AllowUnsafe.embrace.danger)
            unsafe
        }
    end initServer

    /** The individual abort types inside a declared error tag.
      *
      * `ConcreteTag` models a union structurally, so `A | B` decomposes into its members and anything
      * else is a single leaf. Nested unions are flattened, since a body row can be built up in stages.
      */
    private val jsonRpcErrorTag: ConcreteTag[Any] =
        summon[ConcreteTag[JsonRpcError]].asInstanceOf[ConcreteTag[Any]]

    private def errorLeaves(tag: ConcreteTag[Any]): Chunk[ConcreteTag[Any]] =
        tag match
            case ConcreteTag.Union(elements) => Chunk.from(elements).flatMap(errorLeaves)
            case ConcreteTag.NothingTag      => Chunk.empty
            case other                       => Chunk(other)

end McpEngine
