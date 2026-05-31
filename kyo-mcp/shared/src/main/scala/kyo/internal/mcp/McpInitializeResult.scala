package kyo.internal.mcp

import kyo.*

/** Engine-side wire shape for the `initialize` JSON-RPC response from server to client.
  *
  * Used internally by the handshake route; not part of the user-facing API surface.
  */
final private[kyo] case class McpInitializeResult(
    protocolVersion: McpProtocolVersion,
    serverInfo: McpInfo,
    capabilities: McpCapabilities.Server,
    instructions: Maybe[String] = Absent
) derives Schema
