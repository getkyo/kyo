package kyo.internal

import kyo.*
import kyo.internal.mcp.McpCatalog

/** Tests for auto-derivation of McpCapabilities.Server from catalog contents. */
class McpCapabilityDerivationTest extends Test:

    private val testUri = McpResourceUri.parse("file:///x").get

    private val toolRoute     = McpHandler.tool[Unit]("t")((_) => McpContent.Text(""))
    private val resourceRoute = McpHandler.resource(testUri, "r")(Chunk.empty)
    private val promptRoute   = McpHandler.prompt("p", "", Chunk.empty)((_) => McpHandler.PromptOutcome(Absent, Chunk.empty))

    "Case A: declaredCapabilities=Absent with tool, resource, prompt routes" in {
        val config =
            McpConfig.default
                .withAutoNotifyListChanged(true)
        val catalog = McpCatalog(Seq(toolRoute, resourceRoute, promptRoute))
        val caps    = catalog.autoDeriveServerCapabilities(config)

        assert(caps.tools == Present(McpCapabilities.ToolsCapability(listChanged = true)))
        assert(caps.resources == Present(McpCapabilities.ResourcesCapability(subscribe = false, listChanged = true)))
        assert(caps.prompts == Present(McpCapabilities.PromptsCapability(listChanged = true)))
        assert(caps.completions.isEmpty)
        assert(caps.logging.isEmpty)
    }

    "Case A: autoNotifyListChanged=false yields listChanged=false in capabilities" in {
        val config  = McpConfig.default.withAutoNotifyListChanged(false)
        val catalog = McpCatalog(Seq(toolRoute))
        val caps    = catalog.autoDeriveServerCapabilities(config)

        assert(caps.tools == Present(McpCapabilities.ToolsCapability(listChanged = false)))
    }

    "Case B: declaredCapabilities=Present(empty Server) yields empty verbatim" in {
        val empty   = McpCapabilities.Server()
        val config  = McpConfig.default.withDeclaredCapabilities(empty)
        val catalog = McpCatalog(Seq(toolRoute, resourceRoute, promptRoute))
        val caps    = catalog.autoDeriveServerCapabilities(config)

        assert(caps == empty)
        assert(caps.tools.isEmpty)
        assert(caps.resources.isEmpty)
        assert(caps.prompts.isEmpty)
    }

    "no routes: all capabilities absent when declaredCapabilities=Absent" in {
        val config  = McpConfig.default
        val catalog = McpCatalog(Seq.empty)
        val caps    = catalog.autoDeriveServerCapabilities(config)

        assert(caps.tools.isEmpty)
        assert(caps.resources.isEmpty)
        assert(caps.prompts.isEmpty)
        assert(caps.completions.isEmpty)
    }

end McpCapabilityDerivationTest
