package kyo

/** Smoke tests for McpConfig public surface (INV-001 1:1 rule for McpConfig.scala).
  *
  * Focused preset and wiring tests live in McpConfigPresetTest. This file satisfies the
  * Rule 8c requirement that every McpConfig.scala has a matching McpConfigTest.scala.
  */
class McpConfigTest extends Test:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    "McpConfig.default is constructable" in {
        val cfg = McpConfig.default
        assert(cfg.serverInfo.name == "kyo-mcp")
    }

    "McpConfig.HandshakeOrder values are distinct" in {
        assert(
            McpConfig.HandshakeOrder.RequireInitializedNotification !=
                McpConfig.HandshakeOrder.RequireInitializeRequestOnly
        )
    }

    "McpConfig.CapabilityGateMode values are distinct" in {
        assert(McpConfig.CapabilityGateMode.RejectUnsupported != McpConfig.CapabilityGateMode.Off)
        assert(McpConfig.CapabilityGateMode.Off != McpConfig.CapabilityGateMode.LogOnly)
    }

    "McpConfig.require passes for the default config" in {
        McpConfig.require(McpConfig.default)
        succeed
    }

end McpConfigTest
