package kyo

/** Smoke tests for the McpConfig public surface.
  *
  * Focused preset and wiring tests live in McpConfigPresetTest; this file is the matching
  * companion for McpConfig.scala.
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

    "withX fluent setters exist and return updated McpConfig" in {
        val cfg = McpConfig.default
            .withServerInfo(McpInfo("s", "1"))
            .withInstructions("i")
            .withCapabilityGate(McpConfig.CapabilityGateMode.Off)
            .withHandshakeOrder(McpConfig.HandshakeOrder.RequireInitializeRequestOnly)
            .withAutoNotifyListChanged(false)
            .withSupportedProtocolVersions(Set(McpConfig.ProtocolVersion.current))
            .withDeclaredCapabilities(McpCapabilities.Server())
            .withJsonRpc(JsonRpcHandler.Config.default)
        assert(cfg.serverInfo.name == "s")
        assert(cfg.instructions == Present("i"))
        assert(cfg.capabilityGate == McpConfig.CapabilityGateMode.Off)
        assert(cfg.handshakeOrder == McpConfig.HandshakeOrder.RequireInitializeRequestOnly)
        assert(!cfg.autoNotifyListChanged)
        assert(cfg.supportedProtocolVersions == Set(McpConfig.ProtocolVersion.current))
        assert(cfg.declaredCapabilities == Present(McpCapabilities.Server()))
    }

    "withHandshakeTimeout clamps Duration.Zero to 1.milli" in {
        val clamped = McpConfig.default.withHandshakeTimeout(Duration.Zero)
        assert(clamped.handshakeTimeout == 1.milli)
    }

    "withHandshakeTimeout stores positive duration unchanged" in {
        val cfg = McpConfig.default.withHandshakeTimeout(10.seconds)
        assert(cfg.handshakeTimeout == 10.seconds)
    }

    // a client whose peer never responds to initialize aborts with
    // McpHandshakeNotInitializedException once the handshakeTimeout fires.
    // Determinism: the client sends initialize over the tc transport; no server is
    // started on ta, so ta.incoming is never driven and tc.incoming never receives a
    // response. The stall is infinite. Any finite handshakeTimeout deterministically
    // fires, converting the Timeout to McpHandshakeNotInitializedException.
    "handshakeTimeout fires when peer never responds" in {
        val cfg = McpConfig.default.withHandshakeTimeout(5.millis)
        JsonRpcTransport.inMemory.flatMap { (_, tc) =>
            Abort.run[McpException](
                McpClient.initUnscoped(
                    tc,
                    McpInfo("timeout-test"),
                    McpCapabilities.Client(),
                    Seq.empty,
                    cfg
                )
            ).map { result =>
                result match
                    case Result.Failure(e: McpHandshakeNotInitializedException) =>
                        assert(e.isInstanceOf[McpHandshakeNotInitializedException])
                    case Result.Failure(other) =>
                        fail(s"expected McpHandshakeNotInitializedException, got $other")
                    case Result.Panic(t) =>
                        fail(s"expected Failure, got Panic($t)")
                    case Result.Success(c) =>
                        fail(s"expected Failure, got Success($c)")
            }
        }
    }

end McpConfigTest
