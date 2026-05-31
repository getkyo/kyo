package kyo.internal.mcp

import kyo.*

/** Engine-owned `initialize` `JsonRpcRoute` registered at position 0 in the handler (INV-004).
  *
  * Decodes `McpInitializeRequest`, negotiates the protocol version against the server's supported
  * set, writes negotiated state into the provided `AtomicRef`s, and returns `McpInitializeResult`
  * carrying the auto-derived or declared server capabilities.
  *
  * Protocol version negotiation (MCP 2025-06-18 §3.2): if the client's requested version appears
  * in `config.supportedProtocolVersions`, that version is used. If there is no overlap, the server
  * returns success with the highest supported version (`maxBy(_.asString)`) rather than failing.
  * `McpProtocolVersionMismatchError` is kept as a panic-class for the zero-supported-versions case.
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
            val version =
                if config.supportedProtocolVersions.contains(clientVersion) then
                    clientVersion
                else
                    config.supportedProtocolVersions.maxBy(_.asString)
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
            end for
        }

end McpInitializeRoute
