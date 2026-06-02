package kyo

import kyo.internal.mcp.McpCapabilityGate

/** Tests for McpCapabilityGate.server factory.
  *
  * Verifies that the capability gate enforces method admission based on server capabilities
  * and the selected CapabilityGateMode. Only JsonRpcRequest envelopes are checked; notifications
  * always Allow. The gate slot in McpConfig.defaultJsonRpcConfig is Absent until the engine wires it.
  */
class McpCapabilityGateTest extends Test:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    private val emptyServer = McpCapabilities.Server()
    private val fullServer = McpCapabilities.Server(
        tools = Present(McpCapabilities.ToolsCapability()),
        resources = Present(McpCapabilities.ResourcesCapability(subscribe = true)),
        prompts = Present(McpCapabilities.PromptsCapability()),
        logging = Present(McpCapabilities.LoggingCapability()),
        completions = Present(McpCapabilities.CompletionsCapability())
    )

    private def requestEnv(method: String): JsonRpcRequest =
        JsonRpcRequest(JsonRpcId(1L), method, Absent, Absent)

    private def notificationEnv(method: String): JsonRpcNotification =
        JsonRpcNotification(method, Absent, Absent)

    // -------------------------------------------------------------------------
    // gate slot in McpConfig.defaultJsonRpcConfig is Absent
    // -------------------------------------------------------------------------

    "McpConfig.defaultJsonRpcConfig.gate is Absent (engine sets it)" in {
        assert(McpConfig.defaultJsonRpcConfig.gate == Absent)
    }

    // -------------------------------------------------------------------------
    // Mode: Off always allows
    // -------------------------------------------------------------------------

    "Off mode: allows tools/list even with empty capabilities" in run {
        val gate = McpCapabilityGate.server(emptyServer, McpConfig.CapabilityGateMode.Off)
        gate.beforeDispatch(requestEnv("tools/list")).map { dec =>
            assert(dec == JsonRpcMessageGate.Decision.Allow)
        }
    }

    // -------------------------------------------------------------------------
    // Mode: LogOnly always allows
    // -------------------------------------------------------------------------

    "LogOnly mode: allows tools/list even with empty capabilities" in run {
        val gate = McpCapabilityGate.server(emptyServer, McpConfig.CapabilityGateMode.LogOnly)
        gate.beforeDispatch(requestEnv("tools/list")).map { dec =>
            assert(dec == JsonRpcMessageGate.Decision.Allow)
        }
    }

    // -------------------------------------------------------------------------
    // Mode: RejectUnsupported - empty server rejects capability methods
    // -------------------------------------------------------------------------

    "RejectUnsupported: rejects tools/list when tools capability absent" in run {
        val gate = McpCapabilityGate.server(emptyServer, McpConfig.CapabilityGateMode.RejectUnsupported)
        gate.beforeDispatch(requestEnv("tools/list")).map { dec =>
            dec match
                case JsonRpcMessageGate.Decision.Reject(_) => succeed
                case other                                 => fail(s"expected Reject, got $other")
        }
    }

    "RejectUnsupported: rejects tools/call when tools capability absent" in run {
        val gate = McpCapabilityGate.server(emptyServer, McpConfig.CapabilityGateMode.RejectUnsupported)
        gate.beforeDispatch(requestEnv("tools/call")).map { dec =>
            dec match
                case JsonRpcMessageGate.Decision.Reject(_) => succeed
                case other                                 => fail(s"expected Reject, got $other")
        }
    }

    "RejectUnsupported: rejects resources/list when resources capability absent" in run {
        val gate = McpCapabilityGate.server(emptyServer, McpConfig.CapabilityGateMode.RejectUnsupported)
        gate.beforeDispatch(requestEnv("resources/list")).map { dec =>
            dec match
                case JsonRpcMessageGate.Decision.Reject(_) => succeed
                case other                                 => fail(s"expected Reject, got $other")
        }
    }

    "RejectUnsupported: rejects resources/subscribe when subscribe not advertised" in run {
        val capsWithoutSubscribe = McpCapabilities.Server(
            resources = Present(McpCapabilities.ResourcesCapability(subscribe = false))
        )
        val gate = McpCapabilityGate.server(capsWithoutSubscribe, McpConfig.CapabilityGateMode.RejectUnsupported)
        gate.beforeDispatch(requestEnv("resources/subscribe")).map { dec =>
            dec match
                case JsonRpcMessageGate.Decision.Reject(_) => succeed
                case other                                 => fail(s"expected Reject, got $other")
        }
    }

    "RejectUnsupported: rejects prompts/list when prompts capability absent" in run {
        val gate = McpCapabilityGate.server(emptyServer, McpConfig.CapabilityGateMode.RejectUnsupported)
        gate.beforeDispatch(requestEnv("prompts/list")).map { dec =>
            dec match
                case JsonRpcMessageGate.Decision.Reject(_) => succeed
                case other                                 => fail(s"expected Reject, got $other")
        }
    }

    "RejectUnsupported: rejects logging/setLevel when logging capability absent" in run {
        val gate = McpCapabilityGate.server(emptyServer, McpConfig.CapabilityGateMode.RejectUnsupported)
        gate.beforeDispatch(requestEnv("logging/setLevel")).map { dec =>
            dec match
                case JsonRpcMessageGate.Decision.Reject(_) => succeed
                case other                                 => fail(s"expected Reject, got $other")
        }
    }

    "RejectUnsupported: rejects completion/complete when completions capability absent" in run {
        val gate = McpCapabilityGate.server(emptyServer, McpConfig.CapabilityGateMode.RejectUnsupported)
        gate.beforeDispatch(requestEnv("completion/complete")).map { dec =>
            dec match
                case JsonRpcMessageGate.Decision.Reject(_) => succeed
                case other                                 => fail(s"expected Reject, got $other")
        }
    }

    // -------------------------------------------------------------------------
    // RejectUnsupported: full server allows all capability methods
    // -------------------------------------------------------------------------

    "RejectUnsupported: allows tools/list when tools capability present" in run {
        val gate = McpCapabilityGate.server(fullServer, McpConfig.CapabilityGateMode.RejectUnsupported)
        gate.beforeDispatch(requestEnv("tools/list")).map { dec =>
            assert(dec == JsonRpcMessageGate.Decision.Allow)
        }
    }

    "RejectUnsupported: allows resources/subscribe when subscribe advertised" in run {
        val gate = McpCapabilityGate.server(fullServer, McpConfig.CapabilityGateMode.RejectUnsupported)
        gate.beforeDispatch(requestEnv("resources/subscribe")).map { dec =>
            assert(dec == JsonRpcMessageGate.Decision.Allow)
        }
    }

    "RejectUnsupported: allows prompts/get when prompts capability present" in run {
        val gate = McpCapabilityGate.server(fullServer, McpConfig.CapabilityGateMode.RejectUnsupported)
        gate.beforeDispatch(requestEnv("prompts/get")).map { dec =>
            assert(dec == JsonRpcMessageGate.Decision.Allow)
        }
    }

    "RejectUnsupported: allows completion/complete when completions present" in run {
        val gate = McpCapabilityGate.server(fullServer, McpConfig.CapabilityGateMode.RejectUnsupported)
        gate.beforeDispatch(requestEnv("completion/complete")).map { dec =>
            assert(dec == JsonRpcMessageGate.Decision.Allow)
        }
    }

    // -------------------------------------------------------------------------
    // Notifications always Allow (gate only checks requests)
    // -------------------------------------------------------------------------

    "RejectUnsupported: notifications always Allow regardless of capabilities" in run {
        val gate = McpCapabilityGate.server(emptyServer, McpConfig.CapabilityGateMode.RejectUnsupported)
        gate.beforeDispatch(notificationEnv("tools/list")).map { dec =>
            assert(dec == JsonRpcMessageGate.Decision.Allow)
        }
    }

    // -------------------------------------------------------------------------
    // Unknown methods: always Allow (handled by McpUnknownMethodPolicy separately)
    // -------------------------------------------------------------------------

    "RejectUnsupported: unknown method is always admitted by capability gate" in run {
        val gate = McpCapabilityGate.server(emptyServer, McpConfig.CapabilityGateMode.RejectUnsupported)
        gate.beforeDispatch(requestEnv("custom/method")).map { dec =>
            assert(dec == JsonRpcMessageGate.Decision.Allow)
        }
    }

    "RejectUnsupported: initialize method is always admitted" in run {
        val gate = McpCapabilityGate.server(emptyServer, McpConfig.CapabilityGateMode.RejectUnsupported)
        gate.beforeDispatch(requestEnv("initialize")).map { dec =>
            assert(dec == JsonRpcMessageGate.Decision.Allow)
        }
    }

end McpCapabilityGateTest
