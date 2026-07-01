package kyo

/** Tests for McpClient engine wiring. */
class McpClientTest extends Test:

    // Domain types for typed callTool tests.
    case class AddIn(a: Int, b: Int) derives Schema, CanEqual
    case class Sum(value: Int) derives Schema, CanEqual

    private val clientInfo = McpInfo("test-client", "0.0.0")
    private val clientCaps = McpCapabilities.Client()

    // Paired transport helper: runs server then client init over in-memory transports.
    // Server's dispatch loop starts first so it is ready to process the client initialize request.
    private def withPair[A, S](
        serverRoutes: Seq[McpHandler[?, ?, ?]],
        clientRoutes: Seq[McpClientHandler[?, ?, ?]]
    )(f: (McpServer, McpClient) => A < S)(using Frame): A < (S & Async & Scope & Abort[McpException]) =
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            McpServer.init(ta, serverRoutes*).flatMap { server =>
                McpClient.init(tb, clientInfo, clientCaps, clientRoutes*).flatMap { client =>
                    f(server, client)
                }
            }
        }

    // callTool[In, Out] (typed default lane) returns the decoded Out when structuredContent = Present.
    "callTool[In, Out] returns typed Out when structuredContent = Present" in {
        val addRoute = McpHandler.toolRaw[AddIn]("add") { in =>
            McpHandler.ToolOutcome(
                content = Chunk(McpContent.Text(s"${in.a + in.b}")),
                isError = false,
                structuredContent = Present(Structure.encode(Sum(in.a + in.b)))
            )
        }
        withPair(Seq(addRoute), Seq.empty) { (_, client) =>
            client.callTool[Sum]("add")(AddIn(2, 3)).map { result =>
                assert(result == Sum(5))
            }
        }
    }

    // callTool[In, Out] aborts with McpToolStructuredMissingException when structuredContent = Absent.
    "callTool[In, Out] aborts McpToolStructuredMissingException when structuredContent = Absent" in {
        val addUntypedRoute = McpHandler.toolRaw[AddIn]("add") { in =>
            McpHandler.ToolOutcome(
                content = Chunk(McpContent.Text(s"${in.a + in.b}")),
                isError = false,
                structuredContent = Absent
            )
        }
        withPair(Seq(addUntypedRoute), Seq.empty) { (_, client) =>
            Abort.run[McpToolStructuredMissingException](
                client.callTool[Sum]("add")(AddIn(2, 3))
            ).map { result =>
                assert(result.isFailure, s"expected McpToolStructuredMissingException abort, got $result")
                result match
                    case Result.Failure(err) => assert(err.tool == "add")
                    case _                   => fail(s"expected Failure, got $result")
            }
        }
    }

    // callToolRaw[In] (escape hatch) returns raw ToolOutcome without aborting.
    "callToolRaw with one type param returns raw ToolOutcome when structuredContent = Absent" in {
        val addRoute = McpHandler.toolRaw[AddIn]("add") { in =>
            McpHandler.ToolOutcome(
                content = Chunk(McpContent.Text(s"${in.a + in.b}")),
                isError = false,
                structuredContent = Absent
            )
        }
        withPair(Seq(addRoute), Seq.empty) { (_, client) =>
            client.callToolRaw[AddIn]("add", AddIn(2, 3)).map { result =>
                assert(result.structuredContent == Absent)
                assert(result.content.size == 1)
            }
        }
    }

    // McpClient.init positional parameter order is (transport, clientInfo, capabilities, handlers*).
    "McpClient.init positional parameter order is (transport, clientInfo, capabilities, handlers*)" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            // The following must compile with this exact parameter order.
            // Swapping clientInfo and capabilities would fail because McpInfo != McpCapabilities.Client.
            val _: McpClient < (Async & Abort[McpException]) =
                McpClient.initUnscoped(ta, clientInfo, clientCaps)
            succeed
        }
    }

    // listTools returns McpClient.Page[ToolMeta] with .items and .nextCursor fields.
    "listTools returns McpClient.Page with .items and .nextCursor" in {
        val toolRoute = McpHandler.tool[AddIn]("add") { in =>
            McpContent.Text(s"${in.a + in.b}")
        }
        withPair(Seq(toolRoute), Seq.empty) { (_, client) =>
            client.listTools().map { page =>
                assert(page.items.size == 1)
                assert(page.items.head.name == "add")
                assert(page.nextCursor == Absent)
                assert(page.isLast)
            }
        }
    }

    // McpClient.Page is a named record; verify listResources also returns McpClient.Page.
    "listResources returns McpClient.Page with .items and .nextCursor" in {
        val uri           = McpResourceUri.parse("file:///data").get
        val resourceRoute = McpHandler.resource(uri, "data")(Chunk.empty)
        withPair(Seq(resourceRoute), Seq.empty) { (_, client) =>
            client.listResources().map { page =>
                assert(page.items.size == 1)
                assert(page.nextCursor == Absent)
            }
        }
    }

    // Verify close and closeNow type-check correctly.
    "client.close defaults to 30s grace; closeNow is the immediate variant" in {
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            McpServer.init(ta).flatMap { server =>
                McpClient.init(tb, clientInfo, clientCaps).flatMap { client =>
                    // close() must compile as Unit < Async (30s grace)
                    val _closeEffect: Unit < Async = client.close
                    // closeNow must compile as Unit < Async (Duration.Zero)
                    val _closeNowEffect: Unit < Async = client.closeNow
                    succeed
                }
            }
        }
    }

    // Verify the underlying handler is accessible.
    "client.underlying returns a JsonRpcHandler instance" in {
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            McpServer.init(ta).flatMap { server =>
                McpClient.init(tb, clientInfo, clientCaps).flatMap { client =>
                    val _handler: JsonRpcHandler = client.underlying
                    succeed
                }
            }
        }
    }

    // non-Maybe accessors return concrete negotiated values after handshake.
    "serverCapabilities returns concrete Server caps after handshake" in {
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            McpServer.init(ta).flatMap { server =>
                McpClient.init(tb, clientInfo, clientCaps).flatMap { client =>
                    client.serverCapabilities.map { caps =>
                        assert(caps.tools.isEmpty && caps.resources.isEmpty && caps.prompts.isEmpty)
                    }
                }
            }
        }
    }

    "serverInfo returns concrete McpInfo after handshake" in {
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            McpServer.init(ta).flatMap { server =>
                McpClient.init(tb, clientInfo, clientCaps).flatMap { client =>
                    client.serverInfo.map { info =>
                        assert(info.name == "kyo-mcp")
                    }
                }
            }
        }
    }

    "protocolVersion returns the negotiated ProtocolVersion after handshake" in {
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            McpServer.init(ta).flatMap { server =>
                McpClient.init(tb, clientInfo, clientCaps).flatMap { client =>
                    client.protocolVersion.map { ver =>
                        assert(ver == McpConfig.ProtocolVersion.current)
                    }
                }
            }
        }
    }

    // Curried overload must compile.
    "McpClient.initUnscoped curried overload compiles" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            val _: McpClient < (Async & Abort[McpException]) =
                McpClient.initUnscoped(ta, clientInfo, clientCaps, McpConfig.default)()
            succeed
        }
    }

    "McpClient.init curried overload compiles" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            val _: McpClient < (Async & Scope & Abort[McpException]) =
                McpClient.init(ta, clientInfo, clientCaps, McpConfig.default)()
            succeed
        }
    }

    // a present-but-wrong-shape structuredContent surfaces Decode, not Missing.
    "callTool[AddIn, WrongShape] with present-but-undecodable payload aborts McpToolStructuredDecodeException" in {
        case class WrongShape(x: String) derives Schema, CanEqual
        val addRoute = McpHandler.toolRaw[AddIn]("add") { in =>
            McpHandler.ToolOutcome(
                content = Chunk(McpContent.Text(s"${in.a + in.b}")),
                isError = false,
                structuredContent = Present(Structure.encode(Sum(in.a + in.b)))
            )
        }
        withPair(Seq(addRoute), Seq.empty) { (_, client) =>
            Abort.run[McpException](
                client.callTool[WrongShape]("add")(AddIn(2, 3))
            ).map { result =>
                result match
                    case Result.Failure(_: McpToolStructuredDecodeException) => succeed
                    case Result.Failure(_: McpToolStructuredMissingException) =>
                        fail("expected McpToolStructuredDecodeException, got Missing")
                    case other => fail(s"expected McpToolStructuredDecodeException, got $other")
            }
        }
    }

    // second leaf: absent structuredContent surfaces Missing.
    "callTool[AddIn, Sum] with absent structuredContent aborts McpToolStructuredMissingException" in {
        val addRoute = McpHandler.toolRaw[AddIn]("add") { in =>
            McpHandler.ToolOutcome(
                content = Chunk(McpContent.Text(s"${in.a + in.b}")),
                isError = false,
                structuredContent = Absent
            )
        }
        withPair(Seq(addRoute), Seq.empty) { (_, client) =>
            Abort.run[McpException](
                client.callTool[Sum]("add")(AddIn(2, 3))
            ).map { result =>
                result match
                    case Result.Failure(_: McpToolStructuredMissingException) => succeed
                    case other => fail(s"expected McpToolStructuredMissingException, got $other")
            }
        }
    }

    // the local capability guard aborts without sending any wire request.
    "callTool aborts McpCapabilityNotAdvertisedException when tools cap absent" in {
        // Server has no tool routes, so it advertises no tools capability.
        withPair(Seq.empty, Seq.empty) { (_, client) =>
            Abort.run[McpException](
                client.callTool[Sum]("add")(AddIn(1, 1))
            ).map { result =>
                result match
                    case Result.Failure(e: McpCapabilityNotAdvertisedException) =>
                        assert(e.peer == McpCapabilityNotAdvertisedException.Peer.Server)
                    case other => fail(s"expected McpCapabilityNotAdvertisedException, got $other")
            }
        }
    }

    // supports(cap) matches the guard exactly.
    "supports(cap) returns true for advertised cap, false for absent cap" in {
        val toolRoute = McpHandler.toolRaw[AddIn]("add") { in =>
            McpHandler.ToolOutcome(Chunk(McpContent.Text(s"${in.a + in.b}")), false, Absent)
        }
        withPair(Seq(toolRoute), Seq.empty) { (_, client) =>
            client.supports(McpCapabilities.Name.Tools).flatMap { hasTools =>
                client.supports(McpCapabilities.Name.Resources).map { hasResources =>
                    assert(hasTools)
                    assert(!hasResources)
                }
            }
        }
    }

    // drifted structuredContent surfaces a typed McpException, never a panic.
    "drifted structuredContent raises McpToolStructuredDecodeException not a panic" in {
        case class WrongShape(x: String) derives Schema, CanEqual
        val addRoute = McpHandler.toolRaw[AddIn]("add") { in =>
            McpHandler.ToolOutcome(
                content = Chunk(McpContent.Text(s"${in.a + in.b}")),
                isError = false,
                structuredContent = Present(Structure.encode(Sum(in.a + in.b)))
            )
        }
        withPair(Seq(addRoute), Seq.empty) { (_, client) =>
            Abort.run[McpException](
                client.callTool[WrongShape]("add")(AddIn(1, 1))
            ).map { result =>
                result match
                    case Result.Failure(_: McpToolStructuredDecodeException) => succeed
                    case Result.Panic(t)                                     => fail(s"expected typed McpException, got panic: $t")
                    case other => fail(s"expected McpToolStructuredDecodeException, got $other")
            }
        }
    }

    // getPromptChecked rejects unknown and missing args.
    "getPromptChecked rejects unknown argument" in {
        case class PromptOut(text: String) derives Schema, CanEqual
        val promptRoute = McpHandler.prompt(
            "explain",
            "Explain a topic",
            Chunk(McpHandler.PromptArgument("topic", Absent, required = true))
        ) { args =>
            McpHandler.PromptOutcome(
                Absent,
                Chunk(McpHandler.PromptMessage(McpContent.Role.User, McpContent.text(args.getOrElse("topic", ""))))
            )
        }
        withPair(Seq(promptRoute), Seq.empty) { (_, client) =>
            Abort.run[McpException](
                client.getPromptChecked[PromptOut]("explain", Map("bogus" -> "v"))
            ).map { result =>
                result match
                    case Result.Failure(_: McpInvalidArgumentException) => succeed
                    case other => fail(s"expected McpInvalidArgumentException for unknown arg, got $other")
            }
        }
    }

    "getPromptChecked rejects missing required argument" in {
        case class PromptOut(text: String) derives Schema, CanEqual
        val promptRoute = McpHandler.prompt(
            "explain",
            "Explain a topic",
            Chunk(McpHandler.PromptArgument("topic", Absent, required = true))
        ) { args =>
            McpHandler.PromptOutcome(
                Absent,
                Chunk(McpHandler.PromptMessage(McpContent.Role.User, McpContent.text(args.getOrElse("topic", ""))))
            )
        }
        withPair(Seq(promptRoute), Seq.empty) { (_, client) =>
            Abort.run[McpException](
                client.getPromptChecked[PromptOut]("explain", Map.empty)
            ).map { result =>
                result match
                    case Result.Failure(_: McpInvalidArgumentException) => succeed
                    case other => fail(s"expected McpInvalidArgumentException for missing required arg, got $other")
            }
        }
    }

    // streamTools drains all tools across pages.
    "streamTools drains all tools from server" in {
        val route1 = McpHandler.toolRaw[AddIn]("add") { in =>
            McpHandler.ToolOutcome(Chunk(McpContent.Text(s"${in.a + in.b}")), false, Absent)
        }
        withPair(Seq(route1), Seq.empty) { (_, client) =>
            client.streamTools.run.map { tools =>
                assert(tools.size == 1)
                assert(tools.head.name == "add")
            }
        }
    }

    // readResource[Out] typed default lane: happy path returns the concrete decoded value.
    "readResource[Out] decodes JSON text payload to Out" in {
        case class Payload(value: String) derives Schema, CanEqual
        val uri = McpResourceUri.parse("file:///payload").get
        val resourceRoute = McpHandler.resource(uri, "payload") {
            Chunk(McpHandler.ResourceBody.text(Json.encode[Payload](Payload("hello"))))
        }
        withPair(Seq(resourceRoute), Seq.empty) { (_, client) =>
            client.readResource[Payload](uri).map { result =>
                assert(result == Payload("hello"))
            }
        }
    }

    // Invariant: readResource[Out] on a present-but-non-text resource (a Blob leaf) aborts a
    // structured-decode error, not a missing-field error.
    "readResource[Out] aborts McpToolStructuredDecodeException for a Blob resource" in {
        case class Payload(value: String) derives Schema, CanEqual
        val uri = McpResourceUri.parse("file:///blob").get
        val blobRoute = McpHandler.resource(uri, "blob") {
            Chunk(McpHandler.ResourceBody.blob("base64data"))
        }
        withPair(Seq(blobRoute), Seq.empty) { (_, client) =>
            Abort.run[McpToolStructuredDecodeException](
                client.readResource[Payload](uri)
            ).map { result =>
                result match
                    case Result.Failure(e) =>
                        assert(e.tool == "resources/read", s"wrong tool name: ${e.tool}")
                    case other => fail(s"expected McpToolStructuredDecodeException, got $other")
            }
        }
    }

    // getPrompt[Out] typed default lane: happy path decodes _meta to Out.
    "getPrompt[Out] decodes _meta field to Out" in {
        case class Meta(label: String) derives Schema, CanEqual
        val promptRoute = McpHandler.prompt("typed-meta", "a meta prompt", Chunk.empty[McpHandler.PromptArgument]) {
            (_: Map[String, String]) =>
                McpHandler.PromptOutcome(
                    description = Absent,
                    messages = Chunk(McpHandler.PromptMessage(McpContent.Role.User, McpContent.text("hi"))),
                    meta = Present(Structure.encode(Meta("world")))
                )
        }
        withPair(Seq(promptRoute), Seq.empty) { (_, client) =>
            client.getPrompt[Meta]("typed-meta").map { result =>
                assert(result == Meta("world"))
            }
        }
    }

    // getPrompt[Out] typed default lane: absent _meta aborts McpToolStructuredMissingException.
    "getPrompt[Out] aborts McpToolStructuredMissingException when _meta is absent" in {
        case class Meta(label: String) derives Schema, CanEqual
        val promptRoute = McpHandler.prompt("no-meta", "a prompt without meta", Chunk.empty[McpHandler.PromptArgument]) {
            (_: Map[String, String]) =>
                McpHandler.PromptOutcome(
                    description = Absent,
                    messages = Chunk(McpHandler.PromptMessage(McpContent.Role.User, McpContent.text("hi")))
                )
        }
        withPair(Seq(promptRoute), Seq.empty) { (_, client) =>
            Abort.run[McpToolStructuredMissingException](
                client.getPrompt[Meta]("no-meta")
            ).map { result =>
                result match
                    case Result.Failure(_: McpToolStructuredMissingException) => succeed
                    case other => fail(s"expected McpToolStructuredMissingException, got $other")
            }
        }
    }

    // getPrompt[Out] typed default lane: present but non-conforming _meta aborts McpToolStructuredDecodeException.
    "getPrompt[Out] aborts McpToolStructuredDecodeException when _meta does not conform" in {
        case class Meta(label: String) derives Schema, CanEqual
        case class Wrong(x: Int) derives Schema, CanEqual
        val promptRoute = McpHandler.prompt("bad-meta", "a prompt with wrong meta", Chunk.empty[McpHandler.PromptArgument]) {
            (_: Map[String, String]) =>
                McpHandler.PromptOutcome(
                    description = Absent,
                    messages = Chunk(McpHandler.PromptMessage(McpContent.Role.User, McpContent.text("hi"))),
                    meta = Present(Structure.encode(Wrong(42)))
                )
        }
        withPair(Seq(promptRoute), Seq.empty) { (_, client) =>
            Abort.run[McpToolStructuredDecodeException](
                client.getPrompt[Meta]("bad-meta")
            ).map { result =>
                result match
                    case Result.Failure(_: McpToolStructuredDecodeException) => succeed
                    case other => fail(s"expected McpToolStructuredDecodeException, got $other")
            }
        }
    }

end McpClientTest
