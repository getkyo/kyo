package kyo

/** Tests for McpRoute factory implementations (INV-020, INV-024, INV-026). */
class McpRouteTest extends Test:

    case class AddIn(a: Int, b: Int) derives Schema, CanEqual
    case class SumOut(result: Int) derives Schema, CanEqual

    // INV-020: tool and toolMulti are separate factories with distinct Out types.
    "tool factory produces a route with Kind.Tool (INV-020)" in run {
        val r = McpRoute.tool[AddIn]("add").handler { in =>
            McpContent.Text(s"${in.a + in.b}")
        }
        assert(r.kind == McpRoute.Kind.Tool)
        assert(r.name == "add")
    }

    "toolMulti factory produces a route with Kind.Tool and ToolCallResult Out (INV-020)" in run {
        val r = McpRoute.toolMulti[AddIn]("addMulti").handler { in =>
            McpRoute.ToolCallResult(Chunk(McpContent.Text(s"${in.a + in.b}")), isError = false, structuredContent = Absent)
        }
        assert(r.kind == McpRoute.Kind.Tool)
        assert(r.name == "addMulti")
    }

    // INV-024: Mcp.server returns the safe opaque McpServer, not McpServer.Unsafe.
    "Mcp.server returns McpServer (INV-024)" in run {
        // The compile-time assertion is: the value from Mcp.server must be assignable to McpServer.
        val r = McpRoute.tool[Unit]("probe").handler { _ =>
            Mcp.server.map { srv =>
                val _: McpServer = srv
                McpContent.Text("ok")
            }
        }
        JsonRpcTransport.inMemory.map { (ta, _) =>
            McpServer.initUnscoped(ta, r).flatMap { server =>
                val _: McpServer = server
                server.closeNow.andThen(succeed)
            }
        }
    }

    // INV-026: CompletionArg is a named record with name and value fields.
    "CompletionArg is a named record with name and value (INV-026)" in run {
        val arg = McpRoute.CompletionArg(name = "color", value = "red")
        assert(arg.name == "color")
        assert(arg.value == "red")
    }

    "completion factory produces a route with Kind.Custom" in run {
        val ref = McpRoute.CompletionRef.Prompt("myPrompt")
        val r = McpRoute.completion(ref).handler { arg =>
            McpRoute.CompletionResult(Chunk(arg.value), Absent, Absent)
        }
        assert(r.kind == McpRoute.Kind.Custom)
    }

    "resource factory produces a route with Kind.Resource" in run {
        val uri = McpResourceUri.parse("file:///x").get
        val r   = McpRoute.resource(uri, "myRes").handler((_) => Chunk.empty)
        assert(r.kind == McpRoute.Kind.Resource)
        assert(r.name == "myRes")
    }

    "prompt factory produces a route with Kind.Prompt" in run {
        val r = McpRoute.prompt("myPrompt").handler((_) => McpRoute.PromptGetResult(Absent, Chunk.empty))
        assert(r.kind == McpRoute.Kind.Prompt)
        assert(r.name == "myPrompt")
    }

    "custom factory produces a route with Kind.Custom" in run {
        val r = McpRoute.custom[Unit]("myMethod").handler((_) => ())
        assert(r.kind == McpRoute.Kind.Custom)
        assert(r.name == "myMethod")
    }

    "tool handler name matches tool name" in run {
        val r = McpRoute.tool[Unit]("myTool").handler((_) => McpContent.Text(""))
        assert(r.name == "myTool")
    }

    // Optional-field roundtrip tests (Phase 07 / INV-302)

    private def encode[A: Schema](value: A): String = Json.encode[A](value)
    private def decode[A: Schema](json: String): A  = Json.decode[A](json).getOrThrow

    "ToolMeta title Present encodes and round-trips" in {
        val schema = Json.jsonSchema[Unit]
        val meta   = McpRoute.ToolMeta("t", Absent, schema, Absent, Absent, title = Present("My Tool"))
        val json   = encode(meta)
        assert(json.contains("\"title\":\"My Tool\""))
        val back = decode[McpRoute.ToolMeta](json)
        assert(back.title == Present("My Tool"))
    }

    "ToolMeta title Absent omits the key from JSON" in {
        val schema = Json.jsonSchema[Unit]
        val meta   = McpRoute.ToolMeta("t", Absent, schema, Absent, Absent)
        val json   = encode(meta)
        assert(!json.contains("\"title\""))
    }

    "ResourceMeta size Present encodes integer and round-trips" in {
        val uri  = McpResourceUri.parse("file:///r").get
        val meta = McpRoute.ResourceMeta(uri, "r", Absent, Absent, Absent, size = Present(1024L))
        val json = encode(meta)
        assert(json.contains("\"size\":1024"))
        val back = decode[McpRoute.ResourceMeta](json)
        assert(back.size == Present(1024L))
    }

    "ResourceMeta lastModified Present encodes and round-trips" in {
        val uri  = McpResourceUri.parse("file:///r").get
        val meta = McpRoute.ResourceMeta(uri, "r", Absent, Absent, Absent, lastModified = Present("2024-01-01T00:00:00Z"))
        val json = encode(meta)
        assert(json.contains("\"lastModified\":\"2024-01-01T00:00:00Z\""))
        val back = decode[McpRoute.ResourceMeta](json)
        assert(back.lastModified == Present("2024-01-01T00:00:00Z"))
    }

    "ResourceMeta size Absent omits the key from JSON" in {
        val uri  = McpResourceUri.parse("file:///r").get
        val meta = McpRoute.ResourceMeta(uri, "r", Absent, Absent, Absent)
        val json = encode(meta)
        assert(!json.contains("\"size\""))
    }

    "ResourceTemplateMeta title Present encodes and round-trips" in {
        val tpl  = McpResourceUri.Template.parse("file:///{path}").get
        val meta = McpRoute.ResourceTemplateMeta(tpl, "t", Absent, Absent, Absent, title = Present("Tmpl"))
        val json = encode(meta)
        assert(json.contains("\"title\":\"Tmpl\""))
        val back = decode[McpRoute.ResourceTemplateMeta](json)
        assert(back.title == Present("Tmpl"))
    }

    "PromptMeta title Present encodes and round-trips" in {
        val meta = McpRoute.PromptMeta("p", Absent, Chunk.empty, title = Present("P Title"))
        val json = encode(meta)
        assert(json.contains("\"title\":\"P Title\""))
        val back = decode[McpRoute.PromptMeta](json)
        assert(back.title == Present("P Title"))
    }

    "PromptArgument title Present encodes and round-trips" in {
        val arg  = McpRoute.PromptArgument("color", Absent, required = false, title = Present("Color"))
        val json = encode(arg)
        assert(json.contains("\"title\":\"Color\""))
        val back = decode[McpRoute.PromptArgument](json)
        assert(back.title == Present("Color"))
    }

    "ResourceAnnotations lastModified Present encodes and round-trips" in {
        val ann  = McpRoute.ResourceAnnotations(lastModified = Present("2024-06-01T00:00:00Z"))
        val json = encode(ann)
        assert(json.contains("\"lastModified\":\"2024-06-01T00:00:00Z\""))
        val back = decode[McpRoute.ResourceAnnotations](json)
        assert(back.lastModified == Present("2024-06-01T00:00:00Z"))
    }

end McpRouteTest
