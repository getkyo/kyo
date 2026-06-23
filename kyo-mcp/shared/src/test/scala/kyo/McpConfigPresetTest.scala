package kyo

import kyo.internal.mcp.McpCancellationPolicy
import kyo.internal.mcp.McpProgressPolicy
import kyo.internal.mcp.McpUnknownMethodPolicy

/** Tests for McpConfig.default preset wiring and fluent setters.
  *
  * Pins: three MCP policy slots non-default with gate == Absent in defaultJsonRpcConfig,
  * capabilityGate == RejectUnsupported default, serverInfo == McpInfo("kyo-mcp", kyoMcpVersion).
  */
class McpConfigPresetTest extends Test:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    // -------------------------------------------------------------------------
    // Three MCP policy slots are non-default; gate remains Absent
    // -------------------------------------------------------------------------

    "cancellation slot is Present (non-default)" in {
        assert(McpConfig.default.jsonRpc.cancellation.isDefined)
    }

    "cancellation slot differs from JsonRpcHandler.Config.default" in {
        assert(McpConfig.default.jsonRpc.cancellation != JsonRpcHandler.Config.default.cancellation)
    }

    "cancellation cancelMethod is notifications/cancelled" in {
        val policy = McpConfig.default.jsonRpc.cancellation.getOrElse(fail("cancellation absent"))
        assert(policy.cancelMethod == "notifications/cancelled")
    }

    "cancellation protectedMethods contains initialize" in {
        val policy = McpConfig.default.jsonRpc.cancellation.getOrElse(fail("cancellation absent"))
        assert(policy.protectedMethods.contains("initialize"))
    }

    "cancellation expectReplyForCancelledRequest is false" in {
        val policy = McpConfig.default.jsonRpc.cancellation.getOrElse(fail("cancellation absent"))
        assert(!policy.expectReplyForCancelledRequest)
    }

    "progress slot is Present (non-default)" in {
        assert(McpConfig.default.jsonRpc.progress.isDefined)
    }

    "progress slot differs from JsonRpcHandler.Config.default" in {
        assert(McpConfig.default.jsonRpc.progress != JsonRpcHandler.Config.default.progress)
    }

    "progress progressMethod is notifications/progress" in {
        val policy = McpConfig.default.jsonRpc.progress.getOrElse(fail("progress absent"))
        assert(policy.progressMethod == "notifications/progress")
    }

    "progress enforceMonotonic is true" in {
        val policy = McpConfig.default.jsonRpc.progress.getOrElse(fail("progress absent"))
        assert(policy.enforceMonotonic)
    }

    "unknownMethod differs from JsonRpcHandler.Config.default (strict vs minimal)" in {
        assert(McpConfig.default.jsonRpc.unknownMethod != JsonRpcHandler.Config.default.unknownMethod)
    }

    "unknownMethod is eq to JsonRpcUnknownMethodPolicy.strict" in {
        assert(McpConfig.default.jsonRpc.unknownMethod eq JsonRpcUnknownMethodPolicy.strict)
    }

    "gate slot is Absent in defaultJsonRpcConfig (engine wires it)" in {
        assert(McpConfig.default.jsonRpc.gate == Absent)
    }

    // -------------------------------------------------------------------------
    // Passthrough slots: unchanged from JsonRpcHandler.Config.default
    // -------------------------------------------------------------------------

    "maxInFlight matches JsonRpcHandler.Config.default" in {
        assert(McpConfig.default.jsonRpc.maxInFlight == JsonRpcHandler.Config.default.maxInFlight)
    }

    "requestTimeout is Duration.Infinity" in {
        assert(McpConfig.default.jsonRpc.requestTimeout == Duration.Infinity)
    }

    "idStrategy matches JsonRpcHandler.Config.default" in {
        assert(McpConfig.default.jsonRpc.idStrategy == JsonRpcHandler.Config.default.idStrategy)
    }

    "progressResetsTimeout matches JsonRpcHandler.Config.default" in {
        assert(McpConfig.default.jsonRpc.progressResetsTimeout == JsonRpcHandler.Config.default.progressResetsTimeout)
    }

    // -------------------------------------------------------------------------
    // capabilityGate default is RejectUnsupported
    // -------------------------------------------------------------------------

    "capabilityGate default is RejectUnsupported" in {
        assert(McpConfig.default.capabilityGate == McpConfig.CapabilityGateMode.RejectUnsupported)
    }

    // -------------------------------------------------------------------------
    // serverInfo default is McpInfo("kyo-mcp", kyoMcpVersion)
    // -------------------------------------------------------------------------

    "serverInfo default name is kyo-mcp" in {
        assert(McpConfig.default.serverInfo.name == "kyo-mcp")
    }

    "serverInfo default version is McpConfig.ProtocolVersion.kyoMcpVersion" in {
        assert(McpConfig.default.serverInfo.version == McpConfig.ProtocolVersion.kyoMcpVersion)
    }

    // -------------------------------------------------------------------------
    // McpConfig.require: validates constraints
    // -------------------------------------------------------------------------

    "require does not throw for McpConfig.default" in {
        McpConfig.require(McpConfig.default)
        succeed
    }

    "require throws McpConfigurationError for empty supportedProtocolVersions" in {
        val bad = McpConfig.default.withSupportedProtocolVersions(Set.empty)
        val e   = intercept[McpConfigurationError](McpConfig.require(bad))
        assert(e.setting == "supportedProtocolVersions")
        assert(e.reason == "must be non-empty")
    }

    "require throws McpConfigurationError for non-positive handshakeTimeout" in {
        // withHandshakeTimeout clamps to a positive value, so this illegal state is reached via copy;
        // it guards require's defense-in-depth check on the handshakeTimeout slot directly.
        val bad = McpConfig.default.copy(handshakeTimeout = Duration.Zero)
        val e   = intercept[McpConfigurationError](McpConfig.require(bad))
        assert(e.setting == "handshakeTimeout")
        assert(e.reason.startsWith("must be > 0"))
    }

    // -------------------------------------------------------------------------
    // Fluent setters are orthogonal
    // -------------------------------------------------------------------------

    "fluent serverInfo setter does not affect jsonRpc.cancellation" in {
        val modified = McpConfig.default.withServerInfo(McpInfo("my-server", "1.0"))
        assert(modified.jsonRpc.cancellation.isDefined)
        assert(modified.jsonRpc.cancellation.getOrElse(fail("absent")).cancelMethod == "notifications/cancelled")
    }

    "fluent serverInfo setter updates serverInfo field" in {
        val modified = McpConfig.default.withServerInfo(McpInfo("my-server", "1.0"))
        assert(modified.serverInfo.name == "my-server")
        assert(modified.serverInfo.version == "1.0")
    }

    "fluent instructions setter updates instructions field" in {
        val modified = McpConfig.default.withInstructions("some instructions")
        assert(modified.instructions == Present("some instructions"))
    }

    "fluent capabilityGate setter changes the mode" in {
        val modified = McpConfig.default.withCapabilityGate(McpConfig.CapabilityGateMode.Off)
        assert(modified.capabilityGate == McpConfig.CapabilityGateMode.Off)
    }

    "fluent handshakeOrder setter changes the order" in {
        val modified = McpConfig.default.withHandshakeOrder(McpConfig.HandshakeOrder.RequireInitializeRequestOnly)
        assert(modified.handshakeOrder == McpConfig.HandshakeOrder.RequireInitializeRequestOnly)
    }

    "fluent autoNotifyListChanged setter changes the flag" in {
        val modified = McpConfig.default.withAutoNotifyListChanged(false)
        assert(!modified.autoNotifyListChanged)
    }

    "fluent jsonRpc setter replaces the underlying config" in {
        val custom   = JsonRpcHandler.Config.default
        val modified = McpConfig.default.withJsonRpc(custom)
        assert(modified.jsonRpc eq custom)
    }

end McpConfigPresetTest
