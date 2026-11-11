package kyo.integration

import kyo.*
import kyo.schema.rename

/** Tests for _meta pass-through (§3.7): on a ToolOutcome, and on a request's params through Mcp.meta. */
class McpMetaPassThroughTest extends Test:

    case class AddIn(a: Int, b: Int) derives Schema, CanEqual

    case class CallMeta(@rename("claudecode/toolUseId") toolUseId: String) derives Schema, CanEqual

    case class PromptIn(name: String) derives Schema, CanEqual

    case class EchoIn(text: String, @rename("_meta") meta: Maybe[Structure.Value]) derives Schema, CanEqual

    private val callMeta = Structure.Value.Record(Chunk("claudecode/toolUseId" -> Structure.Value.Str("toolu_01")))

    private val fileUri = McpResourceUri.parse("file:///a").get

    private def params(fields: (String, Structure.Value)*)(meta: Maybe[Structure.Value]): Structure.Value =
        Structure.Value.Record(Chunk.from(fields) ++ meta.fold(Chunk.empty)(m => Chunk("_meta" -> m)))

    private val initializeParams =
        params(
            "protocolVersion" -> Structure.Value.Str("2025-06-18"),
            "capabilities"    -> Structure.Value.Record(Chunk.empty),
            "clientInfo"      -> Structure.Value.Record(Chunk(
                "name"    -> Structure.Value.Str("meta-client"),
                "version" -> Structure.Value.Str("0.0.0")
            ))
        )(Absent)

    // Completes the handshake, then sends each request and waits for its response before the next, so the handlers run in the
    // requests' order.
    private def served(handlers: McpHandler[?, ?, ?]*)(requests: (String, Structure.Value)*)(using
        Frame
    ): Chunk[JsonRpcResponse] < (Async & Abort[Closed | McpException | JsonRpcError]) =
        Scope.run {
            JsonRpcTransport.inMemory.flatMap { (serverSide, clientSide) =>
                for
                    _         <- McpServer.init(serverSide, handlers*)
                    _         <- clientSide.send(JsonRpcRequest(JsonRpcId.Num(0), "initialize", Present(initializeParams), Absent))
                    _         <- clientSide.incoming.take(1).run
                    _         <- clientSide.send(JsonRpcNotification("notifications/initialized", Absent, Absent))
                    responses <- Kyo.foreach(Chunk.from(requests).zipWithIndex) { case ((method, request), index) =>
                        clientSide.send(JsonRpcRequest(JsonRpcId.Num(index + 1), method, Present(request), Absent))
                            .andThen(clientSide.incoming.take(1).run)
                    }
                yield responses.flatten.collect { case response: JsonRpcResponse => response }
            }
        }

    "a tool reads the _meta of the tools/call it serves, Absent when the call sent none, and no extras" in {
        for
            reads <- AtomicRef.init(Chunk.empty[(Result[McpDecodeException, Maybe[CallMeta]], Maybe[Structure.Value])])
            tool = McpHandler.toolRaw[AddIn]("add") { in =>
                Abort.run[McpDecodeException](Mcp.metaAs[CallMeta]).map(read =>
                    Mcp.extras.map(extras => reads.updateAndGet(_.append((read, extras))))
                ).andThen(McpHandler.ToolOutcome.ok(McpContent.text(s"${in.a + in.b}")))
            }
            arguments = Structure.encode(AddIn(2, 3))
            responses <- served(tool)(
                "tools/call" -> params("name" -> Structure.Value.Str("add"), "arguments" -> arguments)(Present(callMeta)),
                "tools/call" -> params("name" -> Structure.Value.Str("add"), "arguments" -> arguments)(Absent)
            )
            read <- reads.get
        yield
            assert(responses.map(_.error) == Chunk(Absent, Absent))
            assert(read == Chunk((Result.succeed(Present(CallMeta("toolu_01"))), Absent), (Result.succeed(Absent), Absent)))
        end for
    }

    "a _meta that does not decode to M fails metaAs with McpDecodeException" in {
        for
            reads <- AtomicRef.init(Chunk.empty[Result[McpDecodeException, Maybe[CallMeta]]])
            tool = McpHandler.toolRaw[AddIn]("add") { in =>
                Abort.run[McpDecodeException](Mcp.metaAs[CallMeta]).map(read => reads.updateAndGet(_.append(read)))
                    .andThen(McpHandler.ToolOutcome.ok(McpContent.text(s"${in.a + in.b}")))
            }
            wrong = Structure.Value.Record(Chunk("claudecode/toolUseId" -> Structure.Value.Integer(1L)))
            _ <- served(tool)(
                "tools/call" -> params("name" -> Structure.Value.Str("add"), "arguments" -> Structure.encode(AddIn(2, 3)))(Present(wrong))
            )
            read <- reads.get
        yield read match
            case Chunk(Result.Failure(failure)) => assert(failure.field == "meta")
            case other                          => fail(s"expected one McpDecodeException, got $other")
        end for
    }

    "resources/read, prompts/get and completion/complete hand their handler the _meta they sent, Absent when they sent none" in {
        for
            reads <- AtomicRef.init(Chunk.empty[(String, Maybe[Structure.Value])])
            record     = (name: String) => Mcp.meta.map(meta => reads.updateAndGet(_.append((name, meta))))
            resource   = McpHandler.resource(fileUri, "a")(record("resource").andThen(Chunk(McpHandler.ResourceBody.text("a"))))
            prompt     = McpHandler.prompt("greet", "", Chunk.empty)(_ => record("prompt").andThen(McpHandler.PromptOutcome.user("hi")))
            completion = McpHandler.completion(McpHandler.CompletionRef.Prompt("greet"))(_ =>
                record("completion").andThen(McpHandler.CompletionOutcome.of("v"))
            )
            read     = (meta: Maybe[Structure.Value]) => "resources/read" -> params("uri" -> Structure.Value.Str("file:///a"))(meta)
            get      = (meta: Maybe[Structure.Value]) => "prompts/get" -> params("name" -> Structure.Value.Str("greet"))(meta)
            ref      = Structure.Value.Record(Chunk("type" -> Structure.Value.Str("ref/prompt"), "name" -> Structure.Value.Str("greet")))
            argument = Structure.Value.Record(Chunk("name" -> Structure.Value.Str("a"), "value" -> Structure.Value.Str("v")))
            complete = (meta: Maybe[Structure.Value]) => "completion/complete" -> params("ref" -> ref, "argument" -> argument)(meta)
            responses <- served(resource, prompt, completion)(
                read(Present(callMeta)),
                read(Absent),
                get(Present(callMeta)),
                get(Absent),
                complete(Present(callMeta)),
                complete(Absent)
            )
            recorded <- reads.get
        yield
            assert(responses.map(_.error) == Chunk.fill(6)(Absent))
            assert(recorded == Chunk(
                ("resource", Present(callMeta)),
                ("resource", Absent),
                ("prompt", Present(callMeta)),
                ("prompt", Absent),
                ("completion", Present(callMeta)),
                ("completion", Absent)
            ))
        end for
    }

    "typed tools, typed prompts and resource templates receive request metadata" in {
        for
            reads <- AtomicRef.init(Chunk.empty[(String, Maybe[Structure.Value])])
            record = (name: String) => Mcp.meta.map(meta => reads.updateAndGet(_.append((name, meta))))
            tool   = McpHandler.tool[AddIn]("typed")(in => record("tool").andThen(in.a + in.b))
            prompt = McpHandler.prompt[PromptIn]("typed")(in =>
                record("prompt").andThen(McpHandler.PromptOutcome.user(in.name))
            )
            resource = McpHandler.resourceTemplate(McpResourceUri.Template.parse("file:///items/{id}").get, "items")(_ =>
                record("template").andThen(Chunk(McpHandler.ResourceBody.text("item")))
            )
            responses <- served(tool, prompt, resource)(
                "tools/call" ->
                    params("name" -> Structure.Value.Str("typed"), "arguments" -> Structure.encode(AddIn(2, 3)))(Present(callMeta)),
                "prompts/get" ->
                    params("name" -> Structure.Value.Str("typed"), "arguments" -> Structure.encode(PromptIn("a")))(Present(callMeta)),
                "resources/read" -> params("uri" -> Structure.Value.Str("file:///items/a"))(Present(callMeta))
            )
            recorded <- reads.get
        yield
            assert(responses.map(_.error) == Chunk.fill(3)(Absent))
            assert(recorded == Chunk(("tool", Present(callMeta)), ("prompt", Present(callMeta)), ("template", Present(callMeta))))
        end for
    }

    "concurrent calls retain their own metadata and envelope extras" in {
        for
            bothStarted <- Latch.init(2)
            reads       <- AtomicRef.init(Chunk.empty[(Int, Maybe[Structure.Value], Maybe[Structure.Value])])
            envelopeExtras = Structure.Value.Record(Chunk("traceId" -> Structure.Value.Str("envelope")))
            tool           = McpHandler.toolRaw[AddIn]("parallel") { in =>
                bothStarted.release.andThen(bothStarted.await).andThen {
                    Mcp.meta.map(meta =>
                        Mcp.extras.map(extras => reads.updateAndGet(_.append((in.a, meta, extras))))
                    )
                }.andThen(McpHandler.ToolOutcome.ok(McpContent.text("done")))
            }
            responses <- Scope.run {
                JsonRpcTransport.inMemory.map { (serverSide, clientSide) =>
                    for
                        _ <- McpServer.init(
                            serverSide,
                            McpConfig.default.withJsonRpc(
                                McpConfig.defaultJsonRpcConfig.codec(JsonRpcEnvelope.lenientSchema)
                            )
                        )(tool)
                        _ <- clientSide.send(JsonRpcRequest(JsonRpcId.Num(0), "initialize", Present(initializeParams), Absent))
                        _ <- clientSide.incoming.take(1).run
                        _ <- clientSide.send(JsonRpcNotification("notifications/initialized", Absent, Absent))
                        _ <- clientSide.send(JsonRpcRequest(
                            JsonRpcId.Num(1),
                            "tools/call",
                            Present(params(
                                "name"      -> Structure.Value.Str("parallel"),
                                "arguments" -> Structure.encode(AddIn(1, 0))
                            )(Present(callMeta))),
                            Absent
                        ))
                        _ <- clientSide.send(JsonRpcRequest(
                            JsonRpcId.Num(2),
                            "tools/call",
                            Present(params(
                                "name"      -> Structure.Value.Str("parallel"),
                                "arguments" -> Structure.encode(AddIn(2, 0))
                            )(Absent)),
                            Present(envelopeExtras)
                        ))
                        messages <- clientSide.incoming.take(2).run
                    yield messages.collect { case response: JsonRpcResponse => response }
                }
            }
            recorded <- reads.get
        yield
            assert(responses.map(_.error) == Chunk(Absent, Absent))
            assert(recorded.sortBy(_._1) == Chunk((1, Present(callMeta), Absent), (2, Absent, Present(envelopeExtras))), s"got $recorded")
        end for
    }

    "a handler served on a method of its own reads _meta from its input and is bound none" in {
        for
            reads <- AtomicRef.init(Chunk.empty[(Maybe[Structure.Value], Maybe[Structure.Value])])
            echo = McpHandler.custom[EchoIn]("echo")(in =>
                Mcp.meta.map(meta => reads.updateAndGet(_.append((in.meta, meta)))).andThen(in.text)
            )
            responses <- served(echo)("echo" -> params("text" -> Structure.Value.Str("hi"))(Present(callMeta)))
            read      <- reads.get
        yield
            assert(responses.map(_.error) == Chunk(Absent))
            assert(read == Chunk((Present(callMeta), Absent)))
        end for
    }

    "tool results use the protocol _meta key" in {
        val outcome = McpHandler.ToolOutcome(Chunk.empty, false, Absent, Present(callMeta))
        val json    = s"""{"content":[],"isError":false,"_meta":${Json.encode(callMeta)}}"""
        assert(Json.decode[McpHandler.ToolOutcome](json) == Result.succeed(outcome))
        assert(Json.decode[Structure.Value](Json.encode(outcome)) == Json.decode[Structure.Value](json))
    }

    "prompt results use the protocol _meta key" in {
        val outcome = McpHandler.PromptOutcome(Absent, Chunk.empty, Present(callMeta))
        val json    = s"""{"messages":[],"_meta":${Json.encode(callMeta)}}"""
        assert(Json.decode[McpHandler.PromptOutcome](json) == Result.succeed(outcome))
        assert(Json.decode[Structure.Value](Json.encode(outcome)) == Json.decode[Structure.Value](json))
    }

    "page schemas preserve _meta" in {
        val page = McpClient.Page(Chunk(1), Absent, Present(callMeta))
        val json = s"""{"items":[1],"_meta":${Json.encode(callMeta)}}"""
        assert(Json.decode[McpClient.Page[Int]](json) == Result.succeed(page))
        assert(Json.decode[Structure.Value](Json.encode(page)) == Json.decode[Structure.Value](json))
    }

    "all list methods retain metadata from protocol-shaped results" in {
        def list(method: String, field: String) =
            McpHandler.custom[Structure.Value](method)(_ =>
                Structure.Value.Record(Chunk(field -> Structure.Value.Sequence(Chunk.empty), "_meta" -> callMeta)): Structure.Value
            )
        val config = McpConfig.default.withDeclaredCapabilities(McpCapabilities.Server.of(
            McpCapabilities.Name.Tools,
            McpCapabilities.Name.Resources,
            McpCapabilities.Name.Prompts
        ))
        Scope.run {
            JsonRpcTransport.inMemory.map { (serverSide, clientSide) =>
                for
                    _ <- McpServer.init(serverSide, config)(
                        list("tools/list", "tools"),
                        list("resources/list", "resources"),
                        list("resources/templates/list", "resourceTemplates"),
                        list("prompts/list", "prompts")
                    )
                    client    <- McpClient.init(clientSide, McpInfo("metadata-test"), McpCapabilities.Client())
                    tools     <- client.listTools()
                    resources <- client.listResources()
                    templates <- client.listResourceTemplates()
                    prompts   <- client.listPrompts()
                yield
                    assert(Chunk(tools.meta, resources.meta, templates.meta, prompts.meta) == Chunk.fill(4)(Present(callMeta)))
                    assert(Chunk(tools.items.size, resources.items.size, templates.items.size, prompts.items.size) == Chunk(0, 0, 0, 0))
                end for
            }
        }
    }

    "ToolOutcome meta field round-trips through the wire" in {
        val metaVal   = Structure.Value.Record(Chunk("echo" -> Structure.Value.Str("bar")))
        val toolRoute = McpHandler.toolRaw[AddIn]("add") { in =>
            McpHandler.ToolOutcome(
                content = Chunk(McpContent.text(s"${in.a + in.b}")),
                isError = false,
                structuredContent = Absent,
                meta = Present(metaVal)
            )
        }
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, toolRoute),
                McpClient.initUnscoped(tc, McpInfo("meta-test"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                client.callToolRaw[AddIn]("add", AddIn(2, 3)).flatMap { result =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield assert(result.meta == Present(metaVal), s"expected $metaVal, got ${result.meta}")
                    end for
                }
            }
        }
    }

    "ToolOutcome meta defaults to Absent when not set" in {
        val toolRoute = McpHandler.toolRaw[AddIn]("add") { in =>
            McpHandler.ToolOutcome(
                content = Chunk(McpContent.text(s"${in.a + in.b}")),
                isError = false,
                structuredContent = Absent
            )
        }
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, toolRoute),
                McpClient.initUnscoped(tc, McpInfo("meta-test"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                client.callToolRaw[AddIn]("add", AddIn(2, 3)).flatMap { result =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield
                        // meta defaults to Absent when not provided
                        assert(result.meta == Absent)
                    end for
                }
            }
        }
    }

end McpMetaPassThroughTest
