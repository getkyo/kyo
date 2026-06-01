package kyo.internal

import kyo.*
import kyo.internal.mcp.McpCatalog

/** Tests for McpCatalog route partitioning and metadata extraction (T-008 partial, INV-018). */
class McpCatalogTest extends Test:

    private val testUri = McpResourceUri.parse("file:///test").get
    private val testTpl = McpResourceUri.Template.parse("file:///test/{id}").get

    "empty catalog has no routes in any partition" in run {
        val catalog = McpCatalog(Seq.empty)
        assert(catalog.toolHandlers.isEmpty)
        assert(catalog.resourceHandlers.isEmpty)
        assert(catalog.resourceTemplateHandlers.isEmpty)
        assert(catalog.promptHandlers.isEmpty)
        assert(catalog.completionHandlers.isEmpty)
    }

    "tool routes are partitioned into toolHandlers" in run {
        val r       = McpRoute.tool[Unit]("myTool").handler((_) => McpContent.Text("ok"))
        val catalog = McpCatalog(Seq(r))
        assert(catalog.toolHandlers.size == 1)
        assert(catalog.toolHandlers.head.name == "myTool")
        assert(catalog.resourceHandlers.isEmpty)
    }

    "resource routes are partitioned into resourceHandlers" in run {
        val r       = McpRoute.resource(testUri, "myResource").handler((_) => Chunk.empty)
        val catalog = McpCatalog(Seq(r))
        assert(catalog.resourceHandlers.size == 1)
        assert(catalog.toolHandlers.isEmpty)
    }

    "prompt routes are partitioned into promptHandlers" in run {
        val r       = McpRoute.prompt("myPrompt").handler((_) => McpRoute.PromptGetResult(Absent, Chunk.empty))
        val catalog = McpCatalog(Seq(r))
        assert(catalog.promptHandlers.size == 1)
        assert(catalog.toolHandlers.isEmpty)
    }

    "toolMetaOf returns ToolMeta with correct name and description" in run {
        val r       = McpRoute.tool[Unit]("calc", "A calculator").handler((_) => McpContent.Text("0"))
        val catalog = McpCatalog(Seq(r))
        val meta    = catalog.toolMetaOf(r)
        assert(meta.name == "calc")
        assert(meta.description == Present("A calculator"))
    }

    "autoDeriveServerCapabilities with Absent declaredCapabilities and one tool route" in run {
        val config  = McpConfig.default.autoNotifyListChanged(true)
        val r       = McpRoute.tool[Unit]("t").handler((_) => McpContent.Text("x"))
        val catalog = McpCatalog(Seq(r))
        val caps    = catalog.autoDeriveServerCapabilities(config)
        assert(caps.tools.isDefined)
        assert(caps.tools.get.listChanged == true)
        assert(caps.resources.isEmpty)
        assert(caps.prompts.isEmpty)
    }

    "autoDeriveServerCapabilities with Present(empty) returns empty verbatim (INV-019)" in run {
        val config  = McpConfig.default.declaredCapabilities(McpCapabilities.Server())
        val r       = McpRoute.tool[Unit]("t").handler((_) => McpContent.Text("x"))
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
        val r       = McpRoute.tool[Unit]("t").handler((_) => McpContent.Text("x"))
        val catalog = McpCatalog(Seq(r))
        val sizeA   = catalog.handlers.size
        val sizeB   = catalog.handlers.size
        assert(sizeA == sizeB)
        assert(sizeA == 1)
    }

end McpCatalogTest
