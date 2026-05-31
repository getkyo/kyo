package kyo.internal

import kyo.*
import kyo.internal.mcp.McpCatalog

/** Tests for auto-derivation of McpCapabilities.Server from catalog contents (T-008, INV-019). */
class McpCapabilityDerivationTest extends Test:

    private val testUri = McpResourceUri.parse("file:///x").get

    private val toolRoute     = McpRoute.tool[Unit]("t").handler((_) => McpContent.Text(""))
    private val resourceRoute = McpRoute.resource(testUri, "r").handler((_) => Chunk.empty)
    private val promptRoute   = McpRoute.prompt("p").handler((_) => McpRoute.PromptGetResult(Absent, Chunk.empty))

    "Case A: declaredCapabilities=Absent with tool, resource, prompt routes" in run {
        val config =
            McpConfig.default
                .autoNotifyListChanged(true)
        val catalog = McpCatalog(Seq(toolRoute, resourceRoute, promptRoute))
        val caps    = catalog.autoDeriveServerCapabilities(config)

        assert(caps.tools == Present(McpCapabilities.ToolsCapability(listChanged = true)))
        assert(caps.resources == Present(McpCapabilities.ResourcesCapability(subscribe = false, listChanged = true)))
        assert(caps.prompts == Present(McpCapabilities.PromptsCapability(listChanged = true)))
        assert(caps.completions.isEmpty)
        assert(caps.logging.isEmpty)
    }

    "Case A: autoNotifyListChanged=false yields listChanged=false in capabilities" in run {
        val config  = McpConfig.default.autoNotifyListChanged(false)
        val catalog = McpCatalog(Seq(toolRoute))
        val caps    = catalog.autoDeriveServerCapabilities(config)

        assert(caps.tools == Present(McpCapabilities.ToolsCapability(listChanged = false)))
    }

    "Case B: declaredCapabilities=Present(empty Server) yields empty verbatim (INV-019)" in run {
        val empty   = McpCapabilities.Server()
        val config  = McpConfig.default.declaredCapabilities(empty)
        val catalog = McpCatalog(Seq(toolRoute, resourceRoute, promptRoute))
        val caps    = catalog.autoDeriveServerCapabilities(config)

        assert(caps == empty)
        assert(caps.tools.isEmpty)
        assert(caps.resources.isEmpty)
        assert(caps.prompts.isEmpty)
    }

    "no routes: all capabilities absent when declaredCapabilities=Absent" in run {
        val config  = McpConfig.default
        val catalog = McpCatalog(Seq.empty)
        val caps    = catalog.autoDeriveServerCapabilities(config)

        assert(caps.tools.isEmpty)
        assert(caps.resources.isEmpty)
        assert(caps.prompts.isEmpty)
        assert(caps.completions.isEmpty)
    }

end McpCapabilityDerivationTest
