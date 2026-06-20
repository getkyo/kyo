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
        clientRoutes: Seq[McpHandler[?, ?, ?]]
    )(f: (McpServer, McpClient) => A < S)(using Frame): A < (S & Async & Scope & Abort[McpException | Closed]) =
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            McpServer.init(ta, serverRoutes*).flatMap { server =>
                McpClient.init(tb, clientInfo, clientCaps, clientRoutes*).flatMap { client =>
                    f(server, client)
                }
            }
        }

    // callToolTyped[In, Out] returns the decoded Out when structuredContent = Present;
    // the typed overload decodes structuredContent.
    "callToolTyped[In, Out] returns typed Out when structuredContent = Present" in {
        val addRoute = McpHandler.toolMulti[AddIn]("add") { in =>
            McpHandler.ToolOutcome(
                content = Chunk(McpContent.Text(s"${in.a + in.b}")),
                isError = false,
                structuredContent = Present(Structure.encode(Sum(in.a + in.b)))
            )
        }
        withPair(Seq(addRoute), Seq.empty) { (_, client) =>
            client.callToolTyped[AddIn, Sum]("add", AddIn(2, 3)).map { result =>
                assert(result == Sum(5))
            }
        }
    }

    // callToolTyped[In, Out] aborts with McpToolStructuredMissingException when structuredContent = Absent;
    // the typed overload must abort when structured content is absent.
    "callToolTyped[In, Out] aborts McpToolStructuredMissingException when structuredContent = Absent" in {
        val addUntypedRoute = McpHandler.toolMulti[AddIn]("add") { in =>
            McpHandler.ToolOutcome(
                content = Chunk(McpContent.Text(s"${in.a + in.b}")),
                isError = false,
                structuredContent = Absent
            )
        }
        withPair(Seq(addUntypedRoute), Seq.empty) { (_, client) =>
            Abort.run[McpToolStructuredMissingException](
                client.callToolTyped[AddIn, Sum]("add", AddIn(2, 3))
            ).map { result =>
                assert(result.isFailure, s"expected McpToolStructuredMissingException abort, got $result")
                result match
                    case Result.Failure(err) => assert(err.tool == "add")
                    case _                   => fail(s"expected Failure, got $result")
            }
        }
    }

    // Untyped callTool[In] (one type param) returns raw ToolOutcome without aborting.
    "callTool with one type param (untyped) returns raw ToolOutcome when structuredContent = Absent" in {
        val addRoute = McpHandler.toolMulti[AddIn]("add") { in =>
            McpHandler.ToolOutcome(
                content = Chunk(McpContent.Text(s"${in.a + in.b}")),
                isError = false,
                structuredContent = Absent
            )
        }
        withPair(Seq(addRoute), Seq.empty) { (_, client) =>
            client.callTool[AddIn]("add", AddIn(2, 3)).map { result =>
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
            val _: McpClient < (Async & Abort[McpException | Closed]) =
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

    // Verify server state is populated after handshake.
    "serverCapabilities is Present after handshake" in {
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            McpServer.init(ta).flatMap { server =>
                McpClient.init(tb, clientInfo, clientCaps).flatMap { client =>
                    val caps = client.serverCapabilities
                    assert(caps.isDefined)
                }
            }
        }
    }

    "serverInfo is Present after handshake" in {
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            McpServer.init(ta).flatMap { server =>
                McpClient.init(tb, clientInfo, clientCaps).flatMap { client =>
                    val info = client.serverInfo
                    assert(info.isDefined)
                }
            }
        }
    }

    "protocolVersion is Present after handshake" in {
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            McpServer.init(ta).flatMap { server =>
                McpClient.init(tb, clientInfo, clientCaps).flatMap { client =>
                    val ver = client.protocolVersion
                    assert(ver == Present(McpConfig.ProtocolVersion.current))
                }
            }
        }
    }

    // Curried overload must compile.
    "McpClient.initUnscoped curried overload compiles" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            val _: McpClient < (Async & Abort[McpException | Closed]) =
                McpClient.initUnscoped(ta, clientInfo, clientCaps, McpConfig.default)()
            succeed
        }
    }

    "McpClient.init curried overload compiles" in {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            val _: McpClient < (Async & Scope & Abort[McpException | Closed]) =
                McpClient.init(ta, clientInfo, clientCaps, McpConfig.default)()
            succeed
        }
    }

end McpClientTest
