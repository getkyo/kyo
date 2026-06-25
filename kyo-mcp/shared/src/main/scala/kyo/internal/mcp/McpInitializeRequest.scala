package kyo.internal.mcp

import kyo.*

/** Engine-side wire shape for the `initialize` JSON-RPC request from client to server.
  *
  * Used internally by the handshake route; not part of the user-facing API surface.
  */
final private[kyo] case class McpInitializeRequest(
    protocolVersion: McpConfig.ProtocolVersion,
    clientInfo: McpInfo,
    capabilities: McpCapabilities.Client
) derives Schema
