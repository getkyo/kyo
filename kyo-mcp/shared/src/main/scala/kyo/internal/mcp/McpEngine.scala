package kyo.internal.mcp

import kyo.*

/** Composes all MCP engine components into a live `McpServer.Unsafe` instance.
  *
  * Wiring order (Decision 10):
  *   1. Build `McpCatalog` from user routes.
  *   2. Auto-derive or use declared `McpCapabilities.Server`.
  *   3. Build `McpHandshakeGate` and `McpCapabilityGate`; compose them.
  *   4. Build engine-owned routes (initialize, five builtins).
  *   5. Lift user `McpRoute` instances to `JsonRpcRoute` via their `underlying` field.
  *   6. Call `JsonRpcHandler.initUnscoped` with all routes.
  *   7. Write the `serverRef` so route carriers can resolve `ctx.server`.
  *   8. Return the concrete `McpServer.Unsafe` anonymous class.
  */
private[kyo] object McpEngine:

    // Wire shapes for reverse-direction calls.
    final private case class NotifyEmptyParams() derives Schema
    final private case class ResourceUpdatedParams(uri: String) derives Schema
    final private case class LogMessageParams(level: String, data: Structure.Value, logger: Maybe[String] = Absent) derives Schema

    def initServer(
        transport: JsonRpcTransport,
        userRoutes: Seq[McpRoute[?, ?, ?]],
        config: McpConfig
    )(using Frame): McpServer.Unsafe < Async =
        // AllowUnsafe: AtomicRef for post-handshake state shared across handler fibers.
        // Pattern mirrors JsonRpcMessageGate.server.requireHandshake:77 and McpHandshakeGate.
        val negotiatedVersionRef  = AtomicRef.Unsafe.init[Maybe[McpProtocolVersion]](Absent)(using AllowUnsafe.embrace.danger).safe
        val clientCapabilitiesRef = AtomicRef.Unsafe.init[Maybe[McpCapabilities.Client]](Absent)(using AllowUnsafe.embrace.danger).safe
        val clientInfoRef         = AtomicRef.Unsafe.init[Maybe[McpInfo]](Absent)(using AllowUnsafe.embrace.danger).safe
        // AllowUnsafe: AtomicRef for log level threshold; initialized to Info per §3.9.
        val logLevelRef = AtomicRef.Unsafe.init[McpServer.LogLevel](McpServer.LogLevel.Info)(using AllowUnsafe.embrace.danger).safe
        // AllowUnsafe: AtomicRef for resource subscription set; initialized to empty per §3.4.
        val subscriptionsRef = AtomicRef.Unsafe.init[Set[McpResourceUri]](Set.empty)(using AllowUnsafe.embrace.danger).safe

        val catalog    = McpCatalog(userRoutes)
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
            McpBuiltInRoutes.toolsCall(catalog),
            McpBuiltInRoutes.resourcesList(catalog),
            McpBuiltInRoutes.resourcesRead(catalog),
            McpBuiltInRoutes.resourceTemplatesList(catalog),
            McpBuiltInRoutes.promptsList(catalog),
            McpBuiltInRoutes.promptsGet(catalog),
            McpBuiltInRoutes.completionComplete(catalog)
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
                // AllowUnsafe: synchronous read of client capability; notifications are fire-and-forget.
                val allowed = clientCapabilitiesRef.unsafe.get()(using AllowUnsafe.embrace.danger)
                    .flatMap(_.roots)
                    .exists(_.listChanged)
                if allowed then () else ()
            }

        // MCP 2025-06-18 §3.8: ping must be handled by both client and server.
        // Responds with an empty object to confirm liveness. Registered unconditionally.
        val pingRoute: JsonRpcRoute[?, ?, ?] =
            JsonRpcRoute.request[NotifyEmptyParams, NotifyEmptyParams]("ping") { (_, _) => NotifyEmptyParams() }

        // Lift user McpRoute carriers to JsonRpcRoute.
        // initialize is at index 0 (INV-004); builtins follow; user routes last.
        val userJsonRpcRoutes: Seq[JsonRpcRoute[?, ?, ?]] = userRoutes.map(_.underlying)
        val allRoutes: Seq[JsonRpcRoute[?, ?, ?]] =
            Seq(initializeRoute, initializedNotifRoute, rootsListChangedRoute, pingRoute) ++ builtinRoutes ++ userJsonRpcRoutes

        JsonRpcHandler.initUnscoped(transport, allRoutes, jsonRpcConfig).map { handler =>
            val unsafe: McpServer.Unsafe = new McpServer.Unsafe:

                def requestSamplingUnsafe(req: McpServer.SamplingRequest)(using
                    Frame
                ): McpServer.SamplingResponse < (Async & Abort[McpException | Closed]) =
                    handler.call[McpServer.SamplingRequest, McpServer.SamplingResponse]("sampling/createMessage", req)
                        .handle(Abort.recover[JsonRpcError] { e =>
                            Abort.fail(McpSamplingRejectedException(e.message))
                        })

                def requestRootsUnsafe(using Frame): Chunk[McpServer.Root] < (Async & Abort[McpException | Closed]) =
                    handler.call[NotifyEmptyParams, McpRootsListResponse]("roots/list", NotifyEmptyParams())
                        .map(_.roots)
                        .handle(Abort.recover[JsonRpcError] { e =>
                            Abort.fail(McpInvalidArgumentException("roots/list", "response", e.message))
                        })

                def requestElicitationUnsafe(req: McpServer.ElicitationRequest)(using
                    Frame
                ): McpServer.ElicitationResponse < (Async & Abort[McpException | Closed]) =
                    handler.call[McpServer.ElicitationRequest, McpServer.ElicitationResponse]("elicitation/create", req)
                        .handle(Abort.recover[JsonRpcError] { e =>
                            Abort.fail(McpElicitationDeclinedException(e.message))
                        })

                def notifyToolsListChangedUnsafe(using Frame): Unit < (Async & Abort[Closed]) =
                    serverCaps.tools match
                        case Present(tc) if tc.listChanged =>
                            handler.notify[NotifyEmptyParams]("notifications/tools/list_changed", NotifyEmptyParams())
                        case _ =>
                            Sync.defer(())

                def notifyResourcesListChangedUnsafe(using Frame): Unit < (Async & Abort[Closed]) =
                    serverCaps.resources match
                        case Present(rc) if rc.listChanged =>
                            handler.notify[NotifyEmptyParams]("notifications/resources/list_changed", NotifyEmptyParams())
                        case _ =>
                            Sync.defer(())

                def notifyResourceUpdatedUnsafe(uri: McpResourceUri)(using Frame): Unit < (Async & Abort[Closed]) =
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
                            handler.notify[ResourceUpdatedParams]("notifications/resources/updated", ResourceUpdatedParams(uri.asString))
                        case Absent =>
                            Sync.defer(())

                def notifyPromptsListChangedUnsafe(using Frame): Unit < (Async & Abort[Closed]) =
                    serverCaps.prompts match
                        case Present(pc) if pc.listChanged =>
                            handler.notify[NotifyEmptyParams]("notifications/prompts/list_changed", NotifyEmptyParams())
                        case _ =>
                            Sync.defer(())

                def notifyLogUnsafe[T](level: McpServer.LogLevel, data: T, logger: Maybe[String])(using
                    Frame,
                    Schema[T]
                ): Unit < (Async & Abort[Closed]) =
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
                end notifyLogUnsafe

                def protocolVersionUnsafe: Maybe[McpProtocolVersion] =
                    // AllowUnsafe: synchronous read of post-handshake state (Decision from prep.md §anti-flakiness 3).
                    negotiatedVersionRef.unsafe.get()(using AllowUnsafe.embrace.danger)

                def clientCapabilitiesUnsafe: Maybe[McpCapabilities.Client] =
                    // AllowUnsafe: synchronous read of post-handshake state.
                    clientCapabilitiesRef.unsafe.get()(using AllowUnsafe.embrace.danger)

                def clientInfoUnsafe: Maybe[McpInfo] =
                    // AllowUnsafe: synchronous read of post-handshake state.
                    clientInfoRef.unsafe.get()(using AllowUnsafe.embrace.danger)

                def underlyingUnsafe: JsonRpcHandler = handler

                def awaitDrainUnsafe(using Frame): Unit < Async = handler.awaitDrain

                def closeUnsafe(gracePeriod: Duration)(using Frame): Unit < Async =
                    handler.close(gracePeriod)

            end unsafe

            // Write the forward reference into every user route carrier so their handlers can resolve ctx.server (Decision 2).
            // AllowUnsafe: synchronous write of forward reference immediately after server construction.
            userRoutes.foreach { case c: McpRouteCarrier[?, ?, ?] =>
                c.serverRef.unsafe.set(Present(unsafe))(using AllowUnsafe.embrace.danger)
            }
            unsafe
        }
    end initServer

end McpEngine
