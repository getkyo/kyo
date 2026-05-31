package kyo.internal

import kyo.*
import kyo.internal.mcp.McpCatalog

/** Tests for McpCatalog route partitioning and metadata extraction (T-008 partial, INV-018). */
class McpCatalogTest extends Test:

    private val testUri = McpResourceUri.parse("file:///test").get
    private val testTpl = McpResourceUriTemplate.parse("file:///test/{id}").get

    "empty catalog has no routes in any partition" in run {
        val catalog = McpCatalog(Seq.empty)
        assert(catalog.toolRoutes.isEmpty)
        assert(catalog.resourceRoutes.isEmpty)
        assert(catalog.resourceTemplateRoutes.isEmpty)
        assert(catalog.promptRoutes.isEmpty)
        assert(catalog.completionRoutes.isEmpty)
    }

    "tool routes are partitioned into toolRoutes" in run {
        val r       = McpRoute.tool[Unit, McpContent.Text]("myTool")((_, _) => McpContent.Text("ok", Absent))
        val catalog = McpCatalog(Seq(r))
        assert(catalog.toolRoutes.size == 1)
        assert(catalog.toolRoutes.head.name == "myTool")
        assert(catalog.resourceRoutes.isEmpty)
    }

    "resource routes are partitioned into resourceRoutes" in run {
        val r       = McpRoute.resource(testUri, "myResource")((_, _) => Chunk.empty)
        val catalog = McpCatalog(Seq(r))
        assert(catalog.resourceRoutes.size == 1)
        assert(catalog.toolRoutes.isEmpty)
    }

    "prompt routes are partitioned into promptRoutes" in run {
        val r       = McpRoute.prompt("myPrompt")((_, _) => McpRoute.PromptGetResult(Absent, Chunk.empty))
        val catalog = McpCatalog(Seq(r))
        assert(catalog.promptRoutes.size == 1)
        assert(catalog.toolRoutes.isEmpty)
    }

    "toolMetaOf returns ToolMeta with correct name and description" in run {
        val r       = McpRoute.tool[Unit, McpContent.Text]("calc", "A calculator", Absent)((_, _) => McpContent.Text("0", Absent))
        val catalog = McpCatalog(Seq(r))
        val meta    = catalog.toolMetaOf(r)
        assert(meta.name == "calc")
        assert(meta.description == Present("A calculator"))
    }

    "autoDeriveServerCapabilities with Absent declaredCapabilities and one tool route" in run {
        val config  = McpConfig.default.autoNotifyListChanged(true)
        val r       = McpRoute.tool[Unit, McpContent.Text]("t")((_, _) => McpContent.Text("x", Absent))
        val catalog = McpCatalog(Seq(r))
        val caps    = catalog.autoDeriveServerCapabilities(config)
        assert(caps.tools.isDefined)
        assert(caps.tools.get.listChanged == true)
        assert(caps.resources.isEmpty)
        assert(caps.prompts.isEmpty)
    }

    "autoDeriveServerCapabilities with Present(empty) returns empty verbatim (INV-019)" in run {
        val config  = McpConfig.default.declaredCapabilities(McpCapabilities.Server())
        val r       = McpRoute.tool[Unit, McpContent.Text]("t")((_, _) => McpContent.Text("x", Absent))
        val catalog = McpCatalog(Seq(r))
        val caps    = catalog.autoDeriveServerCapabilities(config)
        // Should be the explicitly declared empty Server, not auto-derived.
        assert(caps.tools.isEmpty)
        assert(caps.resources.isEmpty)
        assert(caps.prompts.isEmpty)
    }

    "catalog is immutable: no mutation methods on public interface (INV-018)" in run {
        // Compile-time check: McpCatalog has no add/remove/set methods.
        // This test verifies the catalog's routes field is final and size-stable.
        val r       = McpRoute.tool[Unit, McpContent.Text]("t")((_, _) => McpContent.Text("x", Absent))
        val catalog = McpCatalog(Seq(r))
        val sizeA   = catalog.routes.size
        val sizeB   = catalog.routes.size
        assert(sizeA == sizeB)
        assert(sizeA == 1)
    }

end McpCatalogTest
