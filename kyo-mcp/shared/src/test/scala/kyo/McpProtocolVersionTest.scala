package kyo

/** Tests for `McpConfig.ProtocolVersion` smart constructor and Schema (Phase 3).
  *
  * Pins INV-025 (no public `def apply` on `McpConfig.ProtocolVersion`) and the
  * `Schema.stringSchema.transform(fromWire)` round-trip.
  */
class McpProtocolVersionTest extends Test:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    "parse: Present for supported version" in {
        val r = McpConfig.ProtocolVersion.parse("2025-06-18")
        assert(r.isDefined)
        assert(r.get.asString == "2025-06-18")
    }

    "parse: Absent for unsupported version" in {
        val r = McpConfig.ProtocolVersion.parse("2099-99-99")
        assert(r.isEmpty)
    }

    "parse: Absent for empty string" in {
        val r = McpConfig.ProtocolVersion.parse("")
        assert(r.isEmpty)
    }

    "Schema round-trip of supported version" in {
        val v       = McpConfig.ProtocolVersion.parse("2025-06-18").get
        val encoded = Structure.encode[McpConfig.ProtocolVersion](v)
        val decoded = Structure.decode[McpConfig.ProtocolVersion](encoded).getOrElse(fail("decode failed"))
        assert(decoded.asString == "2025-06-18")
    }

    "Schema encodes to the underlying string JSON" in {
        val v    = McpConfig.ProtocolVersion.parse("2025-06-18").get
        val json = Json.encode[McpConfig.ProtocolVersion](v)
        assert(json == "\"2025-06-18\"")
    }

    "Schema decodes any wire string via fromWire (no validation at codec level)" in {
        // The codec accepts any string; validation is the handshake gate's job.
        val json = "\"9999-99-99\""
        val v    = Json.decode[McpConfig.ProtocolVersion](json).getOrThrow
        assert(v.asString == "9999-99-99")
    }

    "asString returns the underlying version string" in {
        val v = McpConfig.ProtocolVersion.parse("2025-06-18").get
        assert(v.asString == "2025-06-18")
    }

    "current is the expected version string" in {
        assert(McpConfig.ProtocolVersion.current.asString == "2025-06-18")
    }

end McpProtocolVersionTest
