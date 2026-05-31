package kyo.integration

import kyo.*

/** Tests for McpInfo.title field (§3.20). */
class McpInfoTitleTest extends Test:

    private def encode[A: Schema](value: A): String = Json.encode[A](value)
    private def decode[A: Schema](json: String): A  = Json.decode[A](json).getOrThrow

    "McpInfo with title=Present encodes title field" in {
        val info    = McpInfo("S", "1.0", Present("My Server"))
        val encoded = encode[McpInfo](info)
        assert(encoded.contains("\"title\""), s"expected title in JSON, got: $encoded")
        assert(encoded.contains("\"My Server\""), s"expected title value in JSON, got: $encoded")
    }

    "McpInfo with title=Absent omits title field from JSON" in {
        val info    = McpInfo("S", "1.0", Absent)
        val encoded = encode[McpInfo](info)
        assert(!encoded.contains("\"title\""), s"expected no title in JSON, got: $encoded")
    }

    "McpInfo with title=Present round-trips" in {
        val info    = McpInfo("S", "1.0", Present("My Server"))
        val decoded = decode[McpInfo](encode[McpInfo](info))
        assert(decoded.title == Present("My Server"))
        assert(decoded.name == "S")
        assert(decoded.version == "1.0")
    }

    "McpInfo with title=Absent round-trips with title=Absent" in {
        val info    = McpInfo("S", "1.0")
        val decoded = decode[McpInfo](encode[McpInfo](info))
        assert(decoded.title == Absent)
    }

    "McpInfo default title is Absent" in {
        val info = McpInfo("agent")
        assert(info.title == Absent)
    }

end McpInfoTitleTest
