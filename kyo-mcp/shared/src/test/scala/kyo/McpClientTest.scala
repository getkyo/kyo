package kyo

/** Tests for McpClient engine wiring (T-011, T-012, T-013, INV-014, INV-023, INV-027). */
class McpClientTest extends Test:

    // Domain types for typed callTool tests.
    case class AddIn(a: Int, b: Int) derives Schema, CanEqual
    case class Sum(value: Int) derives Schema, CanEqual

    private val clientInfo = McpInfo("test-client", "0.0.0")
    private val clientCaps = McpCapabilities.Client()

    // Paired transport helper: runs server then client init over in-memory transports.
    // Server's dispatch loop starts first so it is ready to process the client initialize request.
    private def withPair[A, S](
        serverRoutes: Seq[McpRoute[?, ?, ?]],
        clientRoutes: Seq[McpRoute[?, ?, ?]]
    )(f: (McpServer, McpClient) => A < S)(using Frame): A < (S & Async & Scope & Abort[McpError | Closed]) =
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            McpServer.init(ta, serverRoutes*).flatMap { server =>
                McpClient.init(tb, clientInfo, clientCaps, clientRoutes*).flatMap { client =>
                    f(server, client)
                }
            }
        }

    // T-011: callToolTyped[In, Out] returns the decoded Out when structuredContent = Present.
    // INV-027: typed overload decodes structuredContent.
    "callToolTyped[In, Out] returns typed Out when structuredContent = Present (T-011, INV-027)" in run {
        val addRoute = McpRoute.toolMulti[AddIn]("add") { (in, _) =>
            McpRoute.ToolCallResult(
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

    // T-012: callToolTyped[In, Out] aborts with McpToolStructuredMissingError when structuredContent = Absent.
    // INV-027: typed overload must abort when structured content is absent.
    "callToolTyped[In, Out] aborts McpToolStructuredMissingError when structuredContent = Absent (T-012, INV-027)" in run {
        val addUntypedRoute = McpRoute.toolMulti[AddIn]("add") { (in, _) =>
            McpRoute.ToolCallResult(
                content = Chunk(McpContent.Text(s"${in.a + in.b}")),
                isError = false,
                structuredContent = Absent
            )
        }
        withPair(Seq(addUntypedRoute), Seq.empty) { (_, client) =>
            Abort.run[McpToolStructuredMissingError](
                client.callToolTyped[AddIn, Sum]("add", AddIn(2, 3))
            ).map { result =>
                assert(result.isFailure, s"expected McpToolStructuredMissingError abort, got $result")
                result match
                    case Result.Failure(err) => assert(err.tool == "add")
                    case _                   => fail(s"expected Failure, got $result")
            }
        }
    }

    // T-012 untyped: untyped callTool[In] (one type param) returns raw ToolCallResult without aborting.
    "callTool with one type param (untyped) returns raw ToolCallResult when structuredContent = Absent" in run {
        val addRoute = McpRoute.toolMulti[AddIn]("add") { (in, _) =>
            McpRoute.ToolCallResult(
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

    // T-013: McpClient.init positional parameter order is (transport, clientInfo, capabilities, routes*).
    // INV-014: parameter order locked.
    "McpClient.init positional parameter order is (transport, clientInfo, capabilities, routes*) (T-013, INV-014)" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            // The following must compile with this exact parameter order.
            // Swapping clientInfo and capabilities would fail because McpInfo != McpCapabilities.Client.
            val _: McpClient < (Async & Abort[McpError | Closed]) =
                McpClient.initUnscoped(ta, clientInfo, clientCaps)
            succeed
        }
    }

    // INV-023: listTools returns McpPage[ToolMeta] with .items and .nextCursor fields.
    "listTools returns McpPage with .items and .nextCursor (INV-023)" in run {
        val toolRoute = McpRoute.tool[AddIn]("add") { (in, _) =>
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

    // INV-023: McpPage is a named record; verify listResources also returns McpPage.
    "listResources returns McpPage with .items and .nextCursor (INV-023)" in run {
        val uri           = McpResourceUri.parse("file:///data").get
        val resourceRoute = McpRoute.resource(uri, "data")((_, _) => Chunk.empty)
        withPair(Seq(resourceRoute), Seq.empty) { (_, client) =>
            client.listResources().map { page =>
                assert(page.items.size == 1)
                assert(page.nextCursor == Absent)
            }
        }
    }

    // Verify close and closeNow type-check correctly.
    "client.close defaults to 30s grace; closeNow is the immediate variant" in run {
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
    "client.underlying returns a JsonRpcHandler instance" in run {
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
    "serverCapabilities is Present after handshake" in run {
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            McpServer.init(ta).flatMap { server =>
                McpClient.init(tb, clientInfo, clientCaps).flatMap { client =>
                    val caps = client.serverCapabilities
                    assert(caps.isDefined)
                }
            }
        }
    }

    "serverInfo is Present after handshake" in run {
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            McpServer.init(ta).flatMap { server =>
                McpClient.init(tb, clientInfo, clientCaps).flatMap { client =>
                    val info = client.serverInfo
                    assert(info.isDefined)
                }
            }
        }
    }

    "protocolVersion is Present after handshake" in run {
        JsonRpcTransport.inMemory.flatMap { (ta, tb) =>
            McpServer.init(ta).flatMap { server =>
                McpClient.init(tb, clientInfo, clientCaps).flatMap { client =>
                    val ver = client.protocolVersion
                    assert(ver == Present(McpProtocolVersion.current))
                }
            }
        }
    }

    // W2 curried overload must compile.
    "McpClient.initUnscoped curried overload (W2 fix) compiles" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            val _: McpClient < (Async & Abort[McpError | Closed]) =
                McpClient.initUnscoped(ta, clientInfo, clientCaps, McpConfig.default)()
            succeed
        }
    }

    "McpClient.init curried overload (W2 fix) compiles" in run {
        JsonRpcTransport.inMemory.map { (ta, _) =>
            val _: McpClient < (Async & Scope & Abort[McpError | Closed]) =
                McpClient.init(ta, clientInfo, clientCaps, McpConfig.default)()
            succeed
        }
    }

end McpClientTest
