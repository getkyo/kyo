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
      * @param userRoutes user-registered routes (sampling/roots/elicitation handlers among others)
      * @param config     MCP configuration (currently unused; reserved for future gate extension)
      * @return route list to pass to [[JsonRpcHandler.initUnscoped]]
      */
    def buildRoutes(
        userRoutes: Seq[McpRoute[?, ?, ?]],
        config: McpConfig
    )(using Frame): Seq[JsonRpcRoute[?, ?, ?]] =
        val samplingRoute    = buildSamplingRoute(userRoutes)
        val rootsRoute       = buildRootsRoute(userRoutes)
        val elicitationRoute = buildElicitationRoute(userRoutes)

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

    private def buildSamplingRoute(userRoutes: Seq[McpRoute[?, ?, ?]])(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[McpSamplingRequest, McpSamplingResponse]("sampling/createMessage") { (_, _) =>
            // Default handler: no user sampling route registered; reject the server request.
            // Users who want to handle sampling requests register a custom route with
            // name "sampling/createMessage" via McpRoute.custom[McpSamplingRequest, McpSamplingResponse].
            Abort.fail(McpSamplingRejectedError("No sampling handler registered on this client."))
        }

    private def buildRootsRoute(userRoutes: Seq[McpRoute[?, ?, ?]])(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[EmptyParams, McpRootsListResponse]("roots/list") { (_, _) =>
            McpRootsListResponse(Chunk.empty)
        }

    private def buildElicitationRoute(userRoutes: Seq[McpRoute[?, ?, ?]])(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[McpElicitationRequest, McpElicitationResponse]("elicitation/create") { (_, _) =>
            Abort.fail(McpElicitationDeclinedError("No elicitation handler registered on this client."))
        }

end McpReverseDispatch
