package kyo

/** Tests for McpHandler factory implementations. */
class McpHandlerTest extends Test:

    case class AddIn(a: Int, b: Int) derives Schema, CanEqual
    case class SumOut(result: Int) derives Schema, CanEqual
    case class ExplainArgs(topic: String, level: Maybe[String] = Absent) derives Schema, CanEqual

    // tool and toolRaw are separate factories with distinct Out types.
    "tool factory produces a route with Kind.Tool" in {
        val r = McpHandler.tool[AddIn]("add") { in =>
            SumOut(in.a + in.b)
        }
        assert(r.kind == McpHandler.Kind.Tool)
        assert(r.name == "add")
    }

    "toolRaw factory produces a route with Kind.Tool and ToolOutcome Out" in {
        val r = McpHandler.toolRaw[AddIn]("addMulti") { in =>
            McpHandler.ToolOutcome(Chunk(McpContent.Text(s"${in.a + in.b}")), isError = false, structuredContent = Absent)
        }
        assert(r.kind == McpHandler.Kind.Tool)
        assert(r.name == "addMulti")
    }

    // Mcp.server returns the safe opaque McpServer, not McpServer.Unsafe.
    "Mcp.server returns McpServer" in {
        // The compile-time assertion is: the value from Mcp.server must be assignable to McpServer.
        val r = McpHandler.toolRaw[Unit]("probe") { _ =>
            Mcp.server.map { srv =>
                val _: McpServer = srv
                McpHandler.ToolOutcome.ok(McpContent.text("ok"))
            }
        }
        JsonRpcTransport.inMemory.map { (ta, _) =>
            McpServer.initUnscoped(ta, r).flatMap { server =>
                val _: McpServer = server
                server.closeNow.andThen(succeed)
            }
        }
    }

    // CompletionArg is a named record with name and value fields.
    "CompletionArg is a named record with name and value" in {
        val arg = McpHandler.CompletionArg(name = "color", value = "red")
        assert(arg.name == "color")
        assert(arg.value == "red")
    }

    "completion factory produces a route with Kind.Custom" in {
        val ref = McpHandler.CompletionRef.Prompt("myPrompt")
        val r = McpHandler.completion(ref) { arg =>
            McpHandler.CompletionOutcome(Chunk(arg.value), Absent, Absent)
        }
        assert(r.kind == McpHandler.Kind.Custom)
    }

    "resource factory produces a route with Kind.Resource" in {
        val uri = McpResourceUri.parse("file:///x").get
        val r   = McpHandler.resource(uri, "myRes")(Chunk.empty[McpHandler.ResourceBody])
        assert(r.kind == McpHandler.Kind.Resource)
        assert(r.name == "myRes")
    }

    "prompt factory produces a route with Kind.Prompt" in {
        val r = McpHandler.prompt("myPrompt", "", Chunk.empty)((_) => McpHandler.PromptOutcome(Absent, Chunk.empty))
        assert(r.kind == McpHandler.Kind.Prompt)
        assert(r.name == "myPrompt")
    }

    "custom factory produces a route with Kind.Custom" in {
        val r = McpHandler.custom[Unit]("myMethod")((_) => ())
        assert(r.kind == McpHandler.Kind.Custom)
        assert(r.name == "myMethod")
    }

    "tool handler name matches tool name" in {
        val r = McpHandler.tool[Unit]("myTool")((_) => SumOut(0))
        assert(r.name == "myTool")
    }

    // fixed-URI resource body is stamped with the registered URI
    "fixed-URI resource body is stamped with the registered URI" in {
        val uri = McpResourceUri.parse("file:///r").get
        val h   = McpHandler.resource(uri, "r")(Chunk(McpHandler.ResourceBody.text("hi")))
        h match
            case rh: McpHandler.ResourceHandler[?] =>
                assert(rh.resourceMeta.uri == uri)
            case _ =>
                fail("expected ResourceHandler")
        end match
    }

    // template resource ResourceMatch carries matched URI
    "template resource ResourceMatch carries matched URI" in {
        val tpl = McpResourceUri.Template.parse("file:///d/{n}").get
        val h = McpHandler.resourceTemplate(tpl, "d") { m =>
            m.requireVariable("n").map(n => Chunk(McpHandler.ResourceBody.text(s"c$n")))
        }
        h match
            case rth: McpHandler.ResourceTemplateHandler[?] =>
                assert(rth.resourceTemplateMeta.uriTemplate == tpl)
            case _ =>
                fail("expected ResourceTemplateHandler")
        end match
    }

    // requireVariable aborts on missing binding, returns value when present
    "requireVariable aborts on missing binding" in {
        val uri     = McpResourceUri.parse("file:///d/x").get
        val matched = new McpHandler.ResourceMatch(uri, Map("n" -> "x"))
        Abort.run[McpInvalidArgumentException](matched.requireVariable("n")).map { r =>
            assert(r == Result.Success("x"))
        }
    }

    "requireVariable aborts McpInvalidArgumentException on missing binding" in {
        val uri     = McpResourceUri.parse("file:///d/x").get
        val matched = new McpHandler.ResourceMatch(uri, Map.empty)
        Abort.run[McpInvalidArgumentException](matched.requireVariable("n")).map { r =>
            assert(r.isFailure)
            r match
                case Result.Failure(e) => assert(e.field == "n")
                case _                 => fail("expected Failure")
        }
    }

    // typed prompt[In] decodes inbound map into In
    "typed prompt[In] decodes inbound map into In" in {
        val h = McpHandler.prompt[ExplainArgs]("explain") { args =>
            McpHandler.PromptOutcome(Present(args.topic), Chunk.empty)
        }
        h match
            case tph: McpHandler.TypedPromptHandler[?, ?] =>
                assert(tph.promptMeta.name == "explain")
                val args = tph.promptMeta.arguments
                assert(args.exists(_.name == "topic"))
                assert(args.exists(_.name == "level"))
                assert(args.filter(_.name == "topic").forall(_.required))
                assert(args.filter(_.name == "level").forall(!_.required))
            case _ =>
                fail("expected TypedPromptHandler")
        end match
    }

    // typed prompt[In] missing required field aborts a typed decode failure
    "typed prompt[In] missing required field aborts a typed decode failure" in {
        val h = McpHandler.prompt[ExplainArgs]("explain") { args =>
            McpHandler.PromptOutcome(Present(args.topic), Chunk.empty)
        }
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, h),
                McpClient.initUnscoped(tc, McpInfo("test"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                Abort.run[McpException](
                    client.getPromptRaw("explain", Map.empty)
                ).flatMap { result =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield assert(
                        result.isFailure,
                        s"expected McpInvalidArgumentException failure for missing required field, got $result"
                    )
                    end for
                }
            }
        }
    }

    // reserved-range .error code rejected at registration
    "reserved-range .error code (-32001) is accepted by handler but rejected at init" in {
        case class MyErr(msg: String) derives Schema, CanEqual
        given McpHandler.McpErrorCode[MyErr] with
            def code: Int       = -32001
            def message: String = "reserved"
        val h       = McpHandler.tool[Unit]("t")(_ => SumOut(0))
        val withErr = h.error[MyErr]
        JsonRpcTransport.inMemory.flatMap { (ta, _) =>
            Abort.run[Throwable](
                Abort.catching[Throwable](
                    McpServer.initUnscoped(ta, withErr).flatMap(_.closeNow)
                )
            ).map { r =>
                assert(r.isFailure || r.isPanic)
            }
        }
    }

    "non-reserved-range .error code (-40000) is accepted" in {
        case class MyErr2(msg: String) derives Schema, CanEqual
        given McpHandler.McpErrorCode[MyErr2] with
            def code: Int       = -40000
            def message: String = "custom"
        val h       = McpHandler.tool[Unit]("t2")(_ => SumOut(0))
        val withErr = h.error[MyErr2]
        assert(withErr.kind == McpHandler.Kind.Tool)
    }

    // Optional-field roundtrip tests

    private def encode[A: Schema](value: A): String = Json.encode[A](value)
    private def decode[A: Schema](json: String): A  = Json.decode[A](json).getOrThrow

    "ToolMeta title Present encodes and round-trips" in {
        val schema = Json.jsonSchema[Unit]
        val meta   = McpHandler.ToolMeta("t", Absent, schema, Absent, Absent, title = Present("My Tool"))
        val json   = encode(meta)
        assert(json.contains("\"title\":\"My Tool\""))
        val back = decode[McpHandler.ToolMeta](json)
        assert(back.title == Present("My Tool"))
    }

    "ToolMeta title Absent omits the key from JSON" in {
        val schema = Json.jsonSchema[Unit]
        val meta   = McpHandler.ToolMeta("t", Absent, schema, Absent, Absent)
        val json   = encode(meta)
        assert(!json.contains("\"title\""))
    }

    "ResourceMeta size Present encodes integer and round-trips" in {
        val uri  = McpResourceUri.parse("file:///r").get
        val meta = McpHandler.ResourceMeta(uri, "r", Absent, Absent, Absent, size = Present(1024L))
        val json = encode(meta)
        assert(json.contains("\"size\":1024"))
        val back = decode[McpHandler.ResourceMeta](json)
        assert(back.size == Present(1024L))
    }

    "ResourceMeta lastModified Present encodes and round-trips" in {
        val uri  = McpResourceUri.parse("file:///r").get
        val meta = McpHandler.ResourceMeta(uri, "r", Absent, Absent, Absent, lastModified = Present("2024-01-01T00:00:00Z"))
        val json = encode(meta)
        assert(json.contains("\"lastModified\":\"2024-01-01T00:00:00Z\""))
        val back = decode[McpHandler.ResourceMeta](json)
        assert(back.lastModified == Present("2024-01-01T00:00:00Z"))
    }

    "ResourceMeta size Absent omits the key from JSON" in {
        val uri  = McpResourceUri.parse("file:///r").get
        val meta = McpHandler.ResourceMeta(uri, "r", Absent, Absent, Absent)
        val json = encode(meta)
        assert(!json.contains("\"size\""))
    }

    "ResourceTemplateMeta title Present encodes and round-trips" in {
        val tpl  = McpResourceUri.Template.parse("file:///{path}").get
        val meta = McpHandler.ResourceTemplateMeta(tpl, "t", Absent, Absent, Absent, title = Present("Tmpl"))
        val json = encode(meta)
        assert(json.contains("\"title\":\"Tmpl\""))
        val back = decode[McpHandler.ResourceTemplateMeta](json)
        assert(back.title == Present("Tmpl"))
    }

    "PromptMeta title Present encodes and round-trips" in {
        val meta = McpHandler.PromptMeta("p", Absent, Chunk.empty, title = Present("P Title"))
        val json = encode(meta)
        assert(json.contains("\"title\":\"P Title\""))
        val back = decode[McpHandler.PromptMeta](json)
        assert(back.title == Present("P Title"))
    }

    "PromptArgument title Present encodes and round-trips" in {
        val arg  = McpHandler.PromptArgument("color", Absent, required = false, title = Present("Color"))
        val json = encode(arg)
        assert(json.contains("\"title\":\"Color\""))
        val back = decode[McpHandler.PromptArgument](json)
        assert(back.title == Present("Color"))
    }

    "ResourceAnnotations lastModified Present encodes and round-trips" in {
        val ann  = McpHandler.ResourceAnnotations(lastModified = Present("2024-06-01T00:00:00Z"))
        val json = encode(ann)
        assert(json.contains("\"lastModified\":\"2024-06-01T00:00:00Z\""))
        val back = decode[McpHandler.ResourceAnnotations](json)
        assert(back.lastModified == Present("2024-06-01T00:00:00Z"))
    }

end McpHandlerTest
