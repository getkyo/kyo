package kyo.internal.mcp

import kyo.*

/** Engine-owned `initialize` `JsonRpcRoute` registered at position 0 in the handler (INV-004).
  *
  * Decodes `McpInitializeRequest`, negotiates the protocol version against the server's supported
  * set, writes negotiated state into the provided `AtomicRef`s, and returns `McpInitializeResult`
  * carrying the auto-derived or declared server capabilities.
  *
  * Protocol version negotiation: the server accepts the client's requested version if it appears
  * in `config.supportedProtocolVersions`; otherwise returns `McpProtocolVersionMismatchError`
  * which causes the handler framework to send an error response to the client.
  */
private[kyo] object McpInitializeRoute:

    def build(
        config: McpConfig,
        serverCaps: McpCapabilities.Server,
        negotiatedVersionRef: AtomicRef[Maybe[McpProtocolVersion]],
        clientCapabilitiesRef: AtomicRef[Maybe[McpCapabilities.Client]],
        clientInfoRef: AtomicRef[Maybe[McpInfo]]
    )(using Frame): JsonRpcRoute[?, ?, ?] =
        JsonRpcRoute.request[McpInitializeRequest, McpInitializeResult]("initialize") { (req, _) =>
            val clientVersion = req.protocolVersion
            val negotiated =
                if config.supportedProtocolVersions.contains(clientVersion) then
                    Present(clientVersion)
                else
                    Absent
            negotiated match
                case Absent =>
                    Abort.fail(
                        McpProtocolVersionMismatchError(
                            clientRequested = clientVersion,
                            supported = Chunk.from(config.supportedProtocolVersions)
                        )
                    )
                case Present(version) =>
                    for
                        _ <- negotiatedVersionRef.set(Present(version))
                        _ <- clientCapabilitiesRef.set(Present(req.capabilities))
                        _ <- clientInfoRef.set(Present(req.clientInfo))
                    yield McpInitializeResult(
                        protocolVersion = version,
                        serverInfo = config.serverInfo,
                        capabilities = serverCaps,
                        instructions = config.instructions
                    )
            end match
        }

end McpInitializeRoute
