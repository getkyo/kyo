package kyo

/** Tests for `McpResourceUri` smart constructor and Schema (Phase 3).
  *
  * Pins INV-022 (typed McpResourceUri throughout public surface).
  */
class McpResourceUriTest extends Test:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    private def roundtrip(uri: McpResourceUri): McpResourceUri =
        val encoded = Structure.encode[McpResourceUri](uri)
        Structure.decode[McpResourceUri](encoded).getOrElse(fail(s"decode failed for $uri"))

    "parse: Present for valid URI" in {
        val r = McpResourceUri.parse("file:///x")
        assert(r.isDefined)
        assert(r.get.asString == "file:///x")
    }

    "parse: Absent for empty string" in {
        assert(McpResourceUri.parse("").isEmpty)
    }

    "parse: Absent for whitespace-only string" in {
        assert(McpResourceUri.parse("   ").isEmpty)
    }

    "Schema round-trip of valid URI" in {
        val uri = McpResourceUri.apply("file:///x")
        assert(roundtrip(uri) == uri)
    }

    "Schema round-trip preserves custom scheme" in {
        val uri = McpResourceUri.apply("custom://my-server/resource-id")
        assert(roundtrip(uri) == uri)
    }

    "apply produces equal values for same string (CanEqual)" in {
        val a = McpResourceUri.apply("file:///x")
        val b = McpResourceUri.apply("file:///x")
        assert(a == b)
    }

    "asString returns the underlying string" in {
        val uri = McpResourceUri.apply("file:///path/to/resource")
        assert(uri.asString == "file:///path/to/resource")
    }

    "Schema encodes to the underlying string JSON" in {
        val uri  = McpResourceUri.apply("file:///x")
        val json = Json.encode[McpResourceUri](uri)
        assert(json == "\"file:///x\"")
    }

    "Schema decodes from a string JSON" in {
        val json = "\"file:///x\""
        val uri  = Json.decode[McpResourceUri](json).getOrThrow
        assert(uri.asString == "file:///x")
    }

end McpResourceUriTest
