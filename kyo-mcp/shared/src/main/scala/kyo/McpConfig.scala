package kyo

/** Configuration for an MCP server or client.
  *
  * `McpConfig.default` is the recommended starting point; override individual fields via
  * the fluent setter methods. Call `McpConfig.require(config)` before passing to `McpServer.init`
  * to validate field constraints.
  *
  * Phase 1 stub: default body uses `JsonRpcHandler.Config.default` for the `jsonRpc` slot.
  * Phase 4 replaces `defaultJsonRpcConfig` with the four MCP-specific policy overrides.
  *
  * @param serverInfo                 identification advertised in the `initialize` response
  * @param instructions               optional server instructions for the client
  * @param supportedProtocolVersions  MCP protocol versions accepted during handshake
  * @param declaredCapabilities       explicit capabilities; `Absent` triggers auto-derivation
  * @param handshakeTimeout           maximum duration to wait for handshake completion
  * @param handshakeOrder             whether to require the `initialized` notification
  * @param capabilityGate             how to handle requests for unadvertised capabilities
  * @param autoNotifyListChanged      whether to auto-populate `listChanged` in capabilities
  * @param jsonRpc                    underlying JSON-RPC handler configuration
  */
final case class McpConfig(
    serverInfo: McpInfo = McpInfo(name = "kyo-mcp", version = McpProtocolVersion.kyoMcpVersion),
    instructions: Maybe[String] = Absent,
    supportedProtocolVersions: Set[McpProtocolVersion] = McpProtocolVersion.supported,
    declaredCapabilities: Maybe[McpCapabilities.Server] = Absent,
    handshakeTimeout: Duration = 30.seconds,
    handshakeOrder: McpConfig.HandshakeOrder = McpConfig.HandshakeOrder.RequireInitializedNotification,
    capabilityGate: McpConfig.CapabilityGateMode = McpConfig.CapabilityGateMode.RejectUnsupported,
    autoNotifyListChanged: Boolean = true,
    jsonRpc: JsonRpcHandler.Config = McpConfig.defaultJsonRpcConfig
) derives CanEqual:
    def serverInfo(i: McpInfo): McpConfig                                 = copy(serverInfo = i)
    def instructions(s: String): McpConfig                                = copy(instructions = Present(s))
    def supportedProtocolVersions(vs: Set[McpProtocolVersion]): McpConfig = copy(supportedProtocolVersions = vs)
    def declaredCapabilities(c: McpCapabilities.Server): McpConfig        = copy(declaredCapabilities = Present(c))
    def handshakeTimeout(d: Duration): McpConfig                          = copy(handshakeTimeout = d)
    def handshakeOrder(o: McpConfig.HandshakeOrder): McpConfig            = copy(handshakeOrder = o)
    def capabilityGate(m: McpConfig.CapabilityGateMode): McpConfig        = copy(capabilityGate = m)
    def autoNotifyListChanged(b: Boolean): McpConfig                      = copy(autoNotifyListChanged = b)
    def jsonRpc(c: JsonRpcHandler.Config): McpConfig                      = copy(jsonRpc = c)
end McpConfig

object McpConfig:

    /** Controls whether the server requires the `notifications/initialized` notification
      * from the client before dispatching regular requests.
      */
    enum HandshakeOrder derives CanEqual:
        case RequireInitializedNotification
        case RequireInitializeRequestOnly

    /** Controls how the server responds to requests for capabilities it did not advertise. */
    enum CapabilityGateMode derives CanEqual:
        case RejectUnsupported
        case LogOnly
        case Off
    end CapabilityGateMode

    /** Default configuration using auto-derived capabilities and MCP policy overrides.
      * Phase 4 replaces `defaultJsonRpcConfig` with real policy adapters.
      */
    val default: McpConfig = McpConfig()

    /** Validates that `config` satisfies all required constraints.
      * Phase 4 fills the real validation body; Phase 1 stub is a no-op.
      */
    def require(c: McpConfig): Unit = ()

    /** JSON-RPC handler config used by `default`.
      * Phase 1 stub; Phase 4 overrides the four MCP-specific policy slots.
      */
    val defaultJsonRpcConfig: JsonRpcHandler.Config = JsonRpcHandler.Config.default

end McpConfig
