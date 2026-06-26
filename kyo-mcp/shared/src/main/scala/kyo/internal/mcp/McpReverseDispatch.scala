package kyo.internal.mcp

import kyo.*

/** Builds the [[JsonRpcRoute]] instances for server-to-client reverse-direction requests.
  *
  * Three request methods may be issued by the server to the client:
  *   - `sampling/createMessage`: server asks client to run a sampling request
  *   - `roots/list`: server asks client for its registered roots
  *   - `elicitation/create`: server asks client for a structured elicitation
  *
  * For each method, user-registered [[McpHandler]] carriers are searched first. When no user
  * handler covers the method, a default handler returns a rejection response.
  *
  * `private[kyo]` so only [[McpClientEngine]] references it.
  */
private[kyo] object McpReverseDispatch:

    // Wire shape for empty request params (matches McpEngine's NotifyEmptyParams on the server side).
    final private[kyo] case class EmptyParams() derives Schema

    /** Builds the Seq of [[JsonRpcRoute]] instances for client-side reverse-direction handling.
      *
      * Defaults are placed first; user-registered handlers (lifted via [[McpHandlerLift]]) are
      * appended so they override the default-reject fallback on a same-method collision.
      *
      * @param userHandlers       user-registered handlers (sampling/roots/elicitation handlers among others)
      * @param clientCapabilities the client capabilities advertised during handshake; used to gate
      *                           default handlers with -32601 when the relevant capability is absent
      * @param serverRef          the forward reference holding the sentinel "server" used to bind into
      *                           `Mcp.local` for each handler invocation
      * @return route list to pass to [[JsonRpcHandler.initUnscoped]]
      */
    def buildRoutes(
        userHandlers: Seq[McpClientHandler[?, ?, ?]],
        clientCapabilities: McpCapabilities.Client,
        serverRef: AtomicRef[Maybe[McpServer.Unsafe]]
    )(using Frame): Seq[JsonRpcRoute[?, ?, ?]] =
        val samplingRoute    = buildSamplingRoute(clientCapabilities)
        val rootsRoute       = buildRootsRoute(clientCapabilities)
        val elicitationRoute = buildElicitationRoute(clientCapabilities)

        // User-registered client handlers are lifted to JsonRpcRoute (each dispatch binds the
        // request context); JsonRpcHandler stores routes by method with last-write-wins, so a
        // user onSampling/onElicitation/onRoots overrides the default-reject route below it.
        val liftedUserRoutes: Seq[JsonRpcRoute[?, ?, ?]] =
            userHandlers.map(h => h.toRoute(serverRef))

        Seq(samplingRoute, rootsRoute, elicitationRoute) ++ liftedUserRoutes
    end buildRoutes

    private def buildSamplingRoute(clientCapabilities: McpCapabilities.Client)(using
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

    private def buildRootsRoute(clientCapabilities: McpCapabilities.Client)(using
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
                // Capability advertised but no onRoots handler registered: a typed error, never an
                // overloaded Chunk.empty (a real empty workspace comes from a user onRoots returning empty).
                Abort.fail(McpInvalidArgumentException("roots/list", "handler", "no roots handler registered on this client"))
        }

    private def buildElicitationRoute(clientCapabilities: McpCapabilities.Client)(using
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
