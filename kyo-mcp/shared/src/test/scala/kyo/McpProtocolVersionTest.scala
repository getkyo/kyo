package kyo

/** Tests for `McpProtocolVersion` smart constructor and Schema (Phase 3).
  *
  * Pins INV-025 (no public `def apply` on `McpProtocolVersion`) and the
  * `Schema.stringSchema.transform(fromWire)` round-trip.
  */
class McpProtocolVersionTest extends Test:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    "parse: Present for supported version" in {
        val r = McpProtocolVersion.parse("2025-06-18")
        assert(r.isDefined)
        assert(r.get.asString == "2025-06-18")
    }

    "parse: Absent for unsupported version" in {
        val r = McpProtocolVersion.parse("2099-99-99")
        assert(r.isEmpty)
    }

    "parse: Absent for empty string" in {
        val r = McpProtocolVersion.parse("")
        assert(r.isEmpty)
    }

    "Schema round-trip of supported version" in {
        val v       = McpProtocolVersion.parse("2025-06-18").get
        val encoded = Structure.encode[McpProtocolVersion](v)
        val decoded = Structure.decode[McpProtocolVersion](encoded).getOrElse(fail("decode failed"))
        assert(decoded.asString == "2025-06-18")
    }

    "Schema encodes to the underlying string JSON" in {
        val v    = McpProtocolVersion.parse("2025-06-18").get
        val json = Json.encode[McpProtocolVersion](v)
        assert(json == "\"2025-06-18\"")
    }

    "Schema decodes any wire string via fromWire (no validation at codec level)" in {
        // The codec accepts any string; validation is the handshake gate's job.
        val json = "\"9999-99-99\""
        val v    = Json.decode[McpProtocolVersion](json).getOrThrow
        assert(v.asString == "9999-99-99")
    }

    "asString returns the underlying version string" in {
        val v = McpProtocolVersion.parse("2025-06-18").get
        assert(v.asString == "2025-06-18")
    }

    "current is the expected version string" in {
        assert(McpProtocolVersion.current.asString == "2025-06-18")
    }

end McpProtocolVersionTest
