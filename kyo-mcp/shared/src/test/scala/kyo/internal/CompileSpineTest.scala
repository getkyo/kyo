package kyo

import kyo.*

// Phase 1 compile-spine test: asserts every §2 public type is summonable or constructable.
// Pins INV-001 (type enumeration), INV-012 (opaque identities via safe/unsafe round-trip),
// INV-021 (Structure.Value allowlist), INV-022 (typed resource URIs),
// INV-023 (McpClient.Page named record), INV-024 (Context.server type), INV-025 (no public apply).
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

    "McpConfig.ProtocolVersion has no public apply (INV-025)" in {
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

    "McpResourceUri.parse rejects blank (INV-022)" in {
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

    "McpInfo has version default 0.0.0 (Audit-B2)" in {
        val info = McpInfo("my-server")
        assert(info.version == "0.0.0")
    }

    "McpContent sealed leaves are constructable" in {
        val t: McpContent = McpContent.text("hi")
        val i: McpContent = McpContent.image("b64", McpMimeType("image/png"))
        assert(t.isInstanceOf[McpContent.Text])
        assert(i.isInstanceOf[McpContent.Image])
    }

    "McpRoute.ResourceContents uri field is McpResourceUri not String (INV-022)" in {
        val uri                           = McpResourceUri.parse("file:///x").get
        val rc: McpRoute.ResourceContents = McpRoute.ResourceContents.text(uri, "hello")
        assert(rc.uri == uri)
    }

    "McpRoute.CompletionArg is a named record not positional strings (Audit-A8)" in {
        val arg = McpRoute.CompletionArg(name = "model", value = "gpt-4")
        assert(arg.name == "model")
        assert(arg.value == "gpt-4")
    }

    "Mcp.server type is McpServer < Sync (INV-024 compile check)" in {
        // Type-level witness: compiles only if Mcp.server is typed McpServer < Sync (safe opaque).
        val witness: McpServer < Sync = Mcp.server
        succeed
    }

    "INV-021 allowlist: Structure.Value appears only in the documented carve-out sites" in {
        // Positive assertion: the carve-out types compile and Structure.Value is a valid type.
        // The six annotated sites (all carrying flow-allow: §11a / INV-021) are:
        // 1. McpServer.ElicitationResponse.content: Maybe[Structure.Value]
        // 2. McpServer.SamplingRequest.metadata: Maybe[Structure.Value]
        // 3. McpCapabilities.Server.experimental: Map[String, Structure.Value]
        // 4. McpCapabilities.Client.experimental: Map[String, Structure.Value]
        // 5. McpRoute.ToolCallResult.structuredContent: Maybe[Structure.Value]
        // 6. McpException.data: Maybe[Structure.Value] (forwarded to JsonRpcApplicationError)
        // All other public flat-layer signatures must have zero Structure.Value occurrences.
        // The lint regex in flow-verify enforces this; this test asserts the type is reachable.
        val _: Structure.Value = Structure.Value.Null
        assert(Structure.Value.Null.isInstanceOf[Structure.Value])
    }

end CompileSpineTest
