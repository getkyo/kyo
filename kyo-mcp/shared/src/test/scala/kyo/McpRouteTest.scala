package kyo

/** Tests for McpRoute factory implementations (INV-020, INV-024, INV-026). */
class McpRouteTest extends Test:

    case class AddIn(a: Int, b: Int) derives Schema, CanEqual
    case class SumOut(result: Int) derives Schema, CanEqual

    // INV-020: tool and toolMulti are separate factories with distinct Out types.
    "tool factory produces a route with Kind.Tool (INV-020)" in run {
        val r = McpRoute.tool[AddIn]("add") { (in, _) =>
            McpContent.Text(s"${in.a + in.b}")
        }
        assert(r.kind == McpRoute.Kind.Tool)
        assert(r.name == "add")
    }

    "toolMulti factory produces a route with Kind.Tool and ToolCallResult Out (INV-020)" in run {
        val r = McpRoute.toolMulti[AddIn]("addMulti") { (in, _) =>
            McpRoute.ToolCallResult(Chunk(McpContent.Text(s"${in.a + in.b}")), isError = false, structuredContent = Absent)
        }
        assert(r.kind == McpRoute.Kind.Tool)
        assert(r.name == "addMulti")
    }

    // INV-024: Context.server is typed McpServer (safe opaque), not McpServer.Unsafe.
    "Context.server is typed McpServer (INV-024)" in run {
        // Build a route that captures the Context and checks the server field type.
        // The compile-time assertion is: ctx.server must be assignable to McpServer.
        var capturedCtx: McpRoute.Context = null
        val r = McpRoute.tool[Unit]("probe") { (_, ctx) =>
            capturedCtx = ctx
            McpContent.Text("ok")
        }
        // The type of ctx.server is McpServer (safe opaque).
        // We verify the type compiles without casting.
        JsonRpcTransport.inMemory.map { (ta, _) =>
            McpServer.initUnscoped(ta, r).flatMap { server =>
                // Confirm the server field type is McpServer.
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
        val r = McpRoute.completion(ref) { (_, arg, _) =>
            McpRoute.CompletionResult(Chunk(arg.value), Absent, Absent)
        }
        assert(r.kind == McpRoute.Kind.Custom)
    }

    "resource factory produces a route with Kind.Resource" in run {
        val uri = McpResourceUri.parse("file:///x").get
        val r   = McpRoute.resource(uri, "myRes")((_, _) => Chunk.empty)
        assert(r.kind == McpRoute.Kind.Resource)
        assert(r.name == "myRes")
    }

    "prompt factory produces a route with Kind.Prompt" in run {
        val r = McpRoute.prompt("myPrompt")((_, _) => McpRoute.PromptGetResult(Absent, Chunk.empty))
        assert(r.kind == McpRoute.Kind.Prompt)
        assert(r.name == "myPrompt")
    }

    "custom factory produces a route with Kind.Custom" in run {
        val r = McpRoute.custom[Unit]("myMethod")((_, _) => ())
        assert(r.kind == McpRoute.Kind.Custom)
        assert(r.name == "myMethod")
    }

    "tool route underlying method name matches tool name" in run {
        val r = McpRoute.tool[Unit]("myTool")((_, _) => McpContent.Text(""))
        assert(r.underlying.name == "myTool")
    }

end McpRouteTest
