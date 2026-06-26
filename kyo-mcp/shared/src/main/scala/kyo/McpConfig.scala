package kyo

/** Configuration for an MCP server or client.
  *
  * `McpConfig.default` is the recommended starting point; override individual fields via the fluent
  * setter methods. The `init` methods validate the configuration internally (via `McpConfig.require`,
  * which throws a typed `McpConfigurationError` on a rejected field), so explicit pre-validation is not
  * required; the safe fluent setters already make most illegal states unrepresentable.
  *
  * The `jsonRpc` field is pre-populated by `McpConfig.defaultJsonRpcConfig` with MCP-specific policy
  * adapters for cancellation, progress, and unknown-method handling. The capability gate slot is left
  * `Absent` here; the engine sets it during `initServer` after computing the server's advertised
  * capabilities from registered routes.
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
    serverInfo: McpInfo = McpInfo(name = "kyo-mcp", version = McpConfig.ProtocolVersion.kyoMcpVersion),
    instructions: Maybe[String] = Absent,
    supportedProtocolVersions: Set[McpConfig.ProtocolVersion] = McpConfig.ProtocolVersion.supported,
    declaredCapabilities: Maybe[McpCapabilities.Server] = Absent,
    handshakeTimeout: Duration = 30.seconds,
    handshakeOrder: McpConfig.HandshakeOrder = McpConfig.HandshakeOrder.RequireInitializedNotification,
    capabilityGate: McpConfig.CapabilityGateMode = McpConfig.CapabilityGateMode.RejectUnsupported,
    autoNotifyListChanged: Boolean = true,
    jsonRpc: JsonRpcHandler.Config = McpConfig.defaultJsonRpcConfig
) derives CanEqual:
    def withServerInfo(i: McpInfo): McpConfig                                        = copy(serverInfo = i)
    def withInstructions(s: String): McpConfig                                       = copy(instructions = Present(s))
    def withSupportedProtocolVersions(vs: Set[McpConfig.ProtocolVersion]): McpConfig = copy(supportedProtocolVersions = vs)
    def withDeclaredCapabilities(c: McpCapabilities.Server): McpConfig               = copy(declaredCapabilities = Present(c))

    /** Sets the handshake timeout, clamping a non-positive duration up to `1.milli` so the
      * illegal `handshakeTimeout <= 0` state is unrepresentable at the setter. `require` still
      * validates positivity as a belt-and-suspenders floor for the case-class copy path.
      */
    def withHandshakeTimeout(d: Duration): McpConfig =
        copy(handshakeTimeout = if d.toMillis <= 0 then 1.milli else d)
    def withHandshakeOrder(o: McpConfig.HandshakeOrder): McpConfig     = copy(handshakeOrder = o)
    def withCapabilityGate(m: McpConfig.CapabilityGateMode): McpConfig = copy(capabilityGate = m)
    def withAutoNotifyListChanged(b: Boolean): McpConfig               = copy(autoNotifyListChanged = b)
    def withJsonRpc(c: JsonRpcHandler.Config): McpConfig               = copy(jsonRpc = c)
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

    /** Opaque type wrapping the MCP protocol version string.
      *
      * Users construct values via `parse`, which validates the version against the supported set.
      * The engine uses `fromWire` (private[kyo]) to decode versions without validation during
      * the handshake response path. No public `apply` exists.
      *
      * @see [[McpConfig.ProtocolVersion.parse]]
      * @see [[McpConfig.ProtocolVersion.supported]]
      */
    opaque type ProtocolVersion = String

    object ProtocolVersion:

        /** The current MCP protocol version shipped by this library. */
        val current: ProtocolVersion = "2025-06-18"

        /** The kyo-mcp library version string. */
        val kyoMcpVersion: String = "0.1.0"

        /** The set of MCP protocol versions this library accepts during handshake. */
        val supported: Set[ProtocolVersion] = Set("2025-06-18", "2025-11-25")

        /** Returns `Present(v)` when `s` is a supported version string; `Absent` otherwise. */
        def parse(s: String): Maybe[ProtocolVersion] =
            if supported.contains(s) then Present(s) else Absent

        /** Wire decoder: used by the engine to decode a protocol version without client-side validation. */
        private[kyo] def fromWire(s: String): ProtocolVersion = s

        extension (v: ProtocolVersion)
            /** Returns the underlying string value. */
            def asString: String = v

        // Uses `fromWire` (private[kyo] total constructor) so the codec accepts any wire-received string.
        // Client-side validation (supported set check) happens at the handshake gate, not at the codec.
        given Schema[ProtocolVersion] = Schema.stringSchema.transform[ProtocolVersion](fromWire)(_.asString)

        given CanEqual[ProtocolVersion, ProtocolVersion] = CanEqual.derived

    end ProtocolVersion

    /** JSON-RPC handler config used by `default`.
      *
      * Populates three MCP-specific policy slots:
      *   - `cancellation`: MCP `notifications/cancelled` cancellation protocol
      *   - `progress`: MCP `notifications/progress` with `_meta.progressToken` extraction
      *   - `unknownMethod`: strict preset (reject unknown notifications)
      *
      * The `gate` slot is `Absent` here. `McpEngine.initServer` sets it after computing the server's
      * advertised capabilities from registered routes or `McpConfig.declaredCapabilities`.
      *
      * Declared before `default` to avoid Scala object initialization order issues: `default` uses
      * `McpConfig.defaultJsonRpcConfig` as a default parameter value, which must be initialized first.
      */
    val defaultJsonRpcConfig: JsonRpcHandler.Config =
        JsonRpcHandler.Config.default
            .cancellation(internal.mcp.McpCancellationPolicy.default)
            .progress(internal.mcp.McpProgressPolicy.default)
            .unknownMethod(internal.mcp.McpUnknownMethodPolicy.default)
            .codec(summon[Schema[JsonRpcEnvelope]])
            .requestTimeout(Duration.Infinity)

    /** Default configuration using auto-derived capabilities and MCP-specific policy adapters. */
    val default: McpConfig = McpConfig()

    /** Validates that `config` satisfies all required constraints, throwing the first violation as a
      * typed [[McpConfigurationError]] and returning normally when the configuration is valid.
      *
      * A rejected configuration is a construction-time programmer error (the safe fluent setters make
      * most illegal states unrepresentable), so `require` panics rather than surfacing a tracked `Abort`,
      * matching the sibling `JsonRpcHandler.Config.require`.
      *
      * Checks:
      *   - `supportedProtocolVersions` is non-empty.
      *   - `handshakeTimeout` is positive.
      *   - Delegates to `JsonRpcHandler.Config.require` for the `jsonRpc` slot.
      */
    def require(c: McpConfig)(using Frame): Unit =
        if c.supportedProtocolVersions.isEmpty then
            throw McpConfigurationError("supportedProtocolVersions", "must be non-empty")
        else if c.handshakeTimeout.toMillis <= 0 then
            throw McpConfigurationError("handshakeTimeout", s"must be > 0, got ${c.handshakeTimeout}")
        else
            // JsonRpcHandler.Config.require validates the jsonRpc slot; it throws on violation.
            JsonRpcHandler.Config.require(c.jsonRpc)
        end if
    end require

end McpConfig
