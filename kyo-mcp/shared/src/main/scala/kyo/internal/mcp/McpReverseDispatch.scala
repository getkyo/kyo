package kyo.internal.mcp

import kyo.*

/** Builds the [[JsonRpcRoute]] instances for server-to-client reverse-direction requests.
  *
  * Three request methods may be issued by the server to the client:
  *   - `sampling/createMessage`: server asks client to run a sampling request
  *   - `roots/list`: server asks client for its registered roots
  *   - `elicitation/create`: server asks client for a structured elicitation
  *
  * For each method, user-registered [[McpRoute]] carriers are searched first. When no user
  * route covers the method, a default handler returns a rejection response.
  *
  * `private[kyo]` so only [[McpClientEngine]] references it.
  */
private[kyo] object McpReverseDispatch:

    // Wire shape for empty request params (matches McpEngine's NotifyEmptyParams on the server side).
    final private case class EmptyParams() derives Schema

    /** Builds the Seq of [[JsonRpcRoute]] instances for client-side reverse-direction handling.
      *
      * @param userRoutes        user-registered routes (sampling/roots/elicitation handlers among others)
      * @param config            MCP configuration (currently unused; reserved for future gate extension)
      * @param clientCapabilities the client capabilities advertised during handshake; used to gate
      *                           default handlers with -32601 when the relevant capability is absent
      * @return route list to pass to [[JsonRpcHandler.initUnscoped]]
      */
    def buildRoutes(
        userRoutes: Seq[McpRoute[?, ?, ?]],
        config: McpConfig,
        clientCapabilities: McpCapabilities.Client
    )(using Frame): Seq[JsonRpcRoute[?, ?, ?]] =
        val samplingRoute    = buildSamplingRoute(userRoutes, clientCapabilities)
        val rootsRoute       = buildRootsRoute(userRoutes, clientCapabilities)
        val elicitationRoute = buildElicitationRoute(userRoutes, clientCapabilities)

        // Defaults are placed first; user-registered carriers are lifted and appended so that any
        // user route covering sampling/createMessage, roots/list, or elicitation/create overrides
        // the default-reject fallback. JsonRpcHandler routes are stored in a Map keyed by method
        // name with last-write-wins semantics (JsonRpcEndpointImpl.scala:772), so later entries
        // override earlier ones for the same method.
        val liftedUserRoutes: Seq[JsonRpcRoute[?, ?, ?]] = userRoutes.collect {
            case c: McpRouteCarrier[?, ?, ?] => c.underlying
        }

        Seq(samplingRoute, rootsRoute, elicitationRoute) ++ liftedUserRoutes
    end buildRoutes

    private def buildSamplingRoute(userRoutes: Seq[McpRoute[?, ?, ?]], clientCapabilities: McpCapabilities.Client)(using
        Frame
    ): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[McpServer.SamplingRequest, McpServer.SamplingResponse]("sampling/createMessage") { (_, _) =>
            // Default handler: gate on capability first (-32601), then reject if no user handler.
            if clientCapabilities.sampling.isEmpty then
                Abort.fail(McpCapabilityNotAdvertisedException(
                    "sampling/createMessage",
                    McpCapabilities.Name.Sampling,
                    McpCapabilityNotAdvertisedException.Peer.Client
                ))
            else
                Abort.fail(McpSamplingRejectedException("No sampling handler registered on this client."))
        }

    private def buildRootsRoute(userRoutes: Seq[McpRoute[?, ?, ?]], clientCapabilities: McpCapabilities.Client)(using
        Frame
    ): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[EmptyParams, McpRootsListResponse]("roots/list") { (_, _) =>
            if clientCapabilities.roots.isEmpty then
                Abort.fail(McpCapabilityNotAdvertisedException(
                    "roots/list",
                    McpCapabilities.Name.Roots,
                    McpCapabilityNotAdvertisedException.Peer.Client
                ))
            else
                McpRootsListResponse(Chunk.empty)
        }

    private def buildElicitationRoute(userRoutes: Seq[McpRoute[?, ?, ?]], clientCapabilities: McpCapabilities.Client)(using
        Frame
    ): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[McpServer.ElicitationRequest, McpServer.ElicitationResponse]("elicitation/create") { (_, _) =>
            if clientCapabilities.elicitation.isEmpty then
                Abort.fail(McpCapabilityNotAdvertisedException(
                    "elicitation/create",
                    McpCapabilities.Name.Elicitation,
                    McpCapabilityNotAdvertisedException.Peer.Client
                ))
            else
                Abort.fail(McpElicitationDeclinedException("No elicitation handler registered on this client."))
        }

end McpReverseDispatch
