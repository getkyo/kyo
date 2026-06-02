package kyo.internal.mcp

import kyo.*

/** Factory for a JsonRpcMessageGate that enforces MCP capability-gating on inbound request methods.
  *
  * The gate is stateless: `serverCaps` is passed at construction time and reflects the capabilities
  * computed by the engine from registered routes or `McpConfig.declaredCapabilities`. The engine
  * chains this gate after the handshake gate.
  *
  * Three modes (per `McpConfig.CapabilityGateMode`):
  *   - `RejectUnsupported`: rejects inbound requests whose required capability was not advertised.
  *   - `LogOnly`: admits all requests (log-sink wired by the engine).
  *   - `Off`: admits all requests unconditionally (dev/test mode).
  *
  * Only `JsonRpcRequest` messages are capability-checked; notifications and responses always Allow.
  * Methods not in the capability map are always admitted (unknown-method handling is separate).
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
                // The engine replaces this with a gate that logs before admitting once the log-sink is available.
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
    // For sub-flags (e.g. resources.subscribe) the missing capability is still the parent
    // McpCapabilities.Name; the gate-rejection message names the broader capability.
    private def missingCapability(method: String, caps: McpCapabilities.Server): Maybe[McpCapabilities.Name] =
        if method == "tools/list" || method == "tools/call" then
            if caps.tools.isDefined then Absent else Present(McpCapabilities.Name.Tools)
        else if method == "resources/list" || method == "resources/templates/list" || method == "resources/read" then
            if caps.resources.isDefined then Absent else Present(McpCapabilities.Name.Resources)
        else if method == "resources/subscribe" then
            caps.resources match
                case Present(r) if r.subscribe => Absent
                case _                         => Present(McpCapabilities.Name.Resources)
            end match
        else if method == "prompts/list" || method == "prompts/get" then
            if caps.prompts.isDefined then Absent else Present(McpCapabilities.Name.Prompts)
        else if method == "completion/complete" then
            if caps.completions.isDefined then Absent else Present(McpCapabilities.Name.Completions)
        else if method == "logging/setLevel" then
            if caps.logging.isDefined then Absent else Present(McpCapabilities.Name.Logging)
        else
            // Method not in the capability map; always admit
            Absent
    end missingCapability

    // Returns Allow if the method has no capability requirement or the required capability is
    // advertised. Returns Reject with McpCapabilityNotAdvertisedException if the capability is absent.
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
                    val err = McpCapabilityNotAdvertisedException(
                        method = method,
                        requiredCapability = capName,
                        peer = McpCapabilityNotAdvertisedException.Peer.Server
                    )
                    JsonRpcMessageGate.Decision.Reject(JsonRpcResponse.failure(id, err))
            end match
        }
    end requiredCapabilityCheck

end McpCapabilityGate
