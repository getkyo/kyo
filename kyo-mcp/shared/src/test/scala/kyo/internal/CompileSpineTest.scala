package kyo

import kyo.*

// Verifies opaque-type identity round-trip via safe/unsafe accessors and basic constructability
// of the public MCP type spine: each public type is summonable or constructable, opaque types
// expose a witness round-trip, and key named records and typed URI handles behave as documented.
//
// File lives at kyo/internal/CompileSpineTest.scala but declares package kyo so that
// opaque-type internal members (safe/unsafe) are accessible.
class CompileSpineTest extends Test:

    "opaque type identity: McpServer (safe/unsafe round-trip)" in {
        // Verified by compilation: the abstract Unsafe.safe returns McpServer.
        // If McpServer were NOT backed by Unsafe, the abstract method signature would mismatch.
        val witness: McpServer.Unsafe => McpServer = _.safe
        assert(witness != null)
    }

    "opaque type identity: McpClient (safe/unsafe round-trip)" in {
        val witness: McpClient.Unsafe => McpClient = _.safe
        assert(witness != null)
    }

    "opaque type identity: McpConfig.ProtocolVersion (parse/asString round-trip)" in {
        val v = McpConfig.ProtocolVersion.parse("2025-06-18").get
        assert(v.asString == "2025-06-18")
    }

    "McpConfig.ProtocolVersion parse accepts known versions and rejects malformed ones" in {
        val r = McpConfig.ProtocolVersion.parse("2025-06-18")
        assert(r.isDefined)
        val r2 = McpConfig.ProtocolVersion.parse("9999-99-99")
        assert(r2.isEmpty)
    }

    "McpClient.Page is a named record (not a tuple)" in {
        val page = McpClient.Page(Chunk("a", "b"), Absent)
        assert(page.items == Chunk("a", "b"))
        assert(page.nextCursor.isEmpty)
    }

    "McpResourceUri.parse rejects blank strings and accepts well-formed URIs" in {
        assert(McpResourceUri.parse("").isEmpty)
        assert(McpResourceUri.parse("   ").isEmpty)
        assert(McpResourceUri.parse("file:///x").isDefined)
    }

    "McpResourceUri.Template.parse rejects non-template strings" in {
        assert(McpResourceUri.Template.parse("").isEmpty)
        assert(McpResourceUri.Template.parse("file:///x/{path}").isDefined)
    }

    "McpCapabilities.Server and Client are constructable" in {
        val s = McpCapabilities.Server()
        val c = McpCapabilities.Client()
        assert(s.tools.isEmpty)
        assert(c.sampling.isEmpty)
    }

    "McpConfig.default is constructable" in {
        val cfg = McpConfig.default
        assert(cfg.serverInfo.name == "kyo-mcp")
    }

    "McpInfo has version default 0.0.0" in {
        val info = McpInfo("my-server")
        assert(info.version == "0.0.0")
    }

    "McpContent sealed leaves are constructable" in {
        val t: McpContent = McpContent.text("hi")
        val i: McpContent = McpContent.image("b64", McpMimeType("image/png"))
        assert(t.isInstanceOf[McpContent.Text])
        assert(i.isInstanceOf[McpContent.Image])
    }

    "McpHandler.ResourceContents uri field is McpResourceUri not String" in {
        val uri                             = McpResourceUri.parse("file:///x").get
        val rc: McpHandler.ResourceContents = McpHandler.ResourceContents.text(uri, "hello")
        assert(rc.uri == uri)
    }

    "McpHandler.CompletionArg is a named record not positional strings" in {
        val arg = McpHandler.CompletionArg(name = "model", value = "gpt-4")
        assert(arg.name == "model")
        assert(arg.value == "gpt-4")
    }

    "Mcp.server type is McpServer < Sync (safe opaque)" in {
        // Type-level witness: compiles only if Mcp.server is typed McpServer < Sync (safe opaque).
        val witness: McpServer < Sync = Mcp.server
        succeed
    }

    "Structure.Value type is reachable and Null is a valid Structure.Value" in {
        // Positive assertion: Structure.Value is a valid type and Null is a Structure.Value.
        // Public flat-layer signatures restrict Structure.Value to the documented carve-out sites:
        // 1. McpServer.ElicitationResponse.content: Maybe[Structure.Value]
        // 2. McpServer.SamplingRequest.metadata: Maybe[Structure.Value]
        // 3. McpCapabilities.Server.experimental: Map[String, Structure.Value]
        // 4. McpCapabilities.Client.experimental: Map[String, Structure.Value]
        // 5. McpHandler.ToolOutcome.structuredContent: Maybe[Structure.Value]
        // 6. McpException.data: Maybe[Structure.Value] (forwarded to JsonRpcApplicationError)
        val _: Structure.Value = Structure.Value.Null
        assert(Structure.Value.Null.isInstanceOf[Structure.Value])
    }

end CompileSpineTest
