package kyo.internal.mcp

import kyo.*

/** Factory for a JsonRpcMessageGate that enforces MCP capability-gating on inbound request methods.
  *
  * The gate is stateless: `serverCaps` is passed at construction time and reflects the capabilities
  * computed by the engine from registered routes or `McpConfig.declaredCapabilities`. The engine
  * in Phase 5 chains this gate after the handshake gate.
  *
  * Three modes (per `McpConfig.CapabilityGateMode`):
  *   - `RejectUnsupported`: rejects inbound requests whose required capability was not advertised.
  *   - `LogOnly`: admits all requests (log-sink wired by Phase 5 engine).
  *   - `Off`: admits all requests unconditionally (dev/test mode).
  *
  * Only `JsonRpcRequest` messages are capability-checked; notifications and responses always Allow.
  * Methods not in the capability map are always admitted (unknown-method handling is separate).
  *
  * Design ref: design/02-design.md §9:1820-1855.
  */
private[kyo] object McpCapabilityGate:

    /** Returns a `JsonRpcMessageGate` that enforces capability-gating based on `serverCaps` and `mode`.
      *
      * @param serverCaps  the advertised server capabilities computed at engine init time
      * @param mode        the gating mode controlling rejection vs. log-only vs. off behavior
      */
    def server(
        serverCaps: McpCapabilities.Server,
        mode: McpConfig.CapabilityGateMode
    ): JsonRpcMessageGate =
        mode match
            case McpConfig.CapabilityGateMode.Off =>
                JsonRpcMessageGate.noop

            case McpConfig.CapabilityGateMode.LogOnly =>
                // Phase 5 engine replaces this with a gate that logs before admitting.
                // In Phase 4 the log-sink is not available, so simply admit.
                JsonRpcMessageGate.noop

            case McpConfig.CapabilityGateMode.RejectUnsupported =>
                new JsonRpcMessageGate:
                    def beforeDispatch(env: JsonRpcEnvelope)(using Frame): JsonRpcMessageGate.Decision < Sync =
                        env match
                            case req: JsonRpcRequest =>
                                requiredCapabilityCheck(req.id, req.method, serverCaps)
                            case _ =>
                                Sync.defer(JsonRpcMessageGate.Decision.Allow)
                        end match
                    end beforeDispatch
                end new
        end match
    end server

    // Returns the missing capability name, or Absent if the method is admitted.
    // Methods not in the capability map always return Absent (admitted).
    private def missingCapability(method: String, caps: McpCapabilities.Server): Maybe[String] =
        if method == "tools/list" || method == "tools/call" then
            if caps.tools.isDefined then Absent else Present("tools")
        else if method == "resources/list" || method == "resources/templates/list" || method == "resources/read" then
            if caps.resources.isDefined then Absent else Present("resources")
        else if method == "resources/subscribe" then
            caps.resources match
                case Present(r) if r.subscribe => Absent
                case _                         => Present("resources.subscribe")
            end match
        else if method == "prompts/list" || method == "prompts/get" then
            if caps.prompts.isDefined then Absent else Present("prompts")
        else if method == "completion/complete" then
            if caps.completions.isDefined then Absent else Present("completions")
        else if method == "logging/setLevel" then
            if caps.logging.isDefined then Absent else Present("logging")
        else
            // Method not in the capability map; always admit
            Absent
    end missingCapability

    // Returns Allow if the method has no capability requirement or the required capability is
    // advertised. Returns Reject with McpCapabilityNotAdvertisedError if the capability is absent.
    private def requiredCapabilityCheck(
        id: JsonRpcId,
        method: String,
        caps: McpCapabilities.Server
    )(using Frame): JsonRpcMessageGate.Decision < Sync =
        Sync.defer {
            missingCapability(method, caps) match
                case Absent =>
                    JsonRpcMessageGate.Decision.Allow
                case Present(capName) =>
                    val err = McpCapabilityNotAdvertisedError(
                        method = method,
                        requiredCapability = capName,
                        peer = McpCapabilityNotAdvertisedError.Peer.Server
                    )
                    JsonRpcMessageGate.Decision.Reject(JsonRpcResponse.failure(id, err))
            end match
        }
    end requiredCapabilityCheck

end McpCapabilityGate
