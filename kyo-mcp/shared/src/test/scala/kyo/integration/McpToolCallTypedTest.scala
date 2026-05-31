package kyo.integration

import kyo.*

/** Integration test: typed tool call happy-path roundtrip (INV-027). */
class McpToolCallTypedTest extends Test:

    case class AddIn(a: Int, b: Int) derives Schema, CanEqual
    case class Sum(value: Int) derives Schema, CanEqual

    private val addRoute = McpRoute.toolMulti[AddIn]("add") { (in, _) =>
        McpRoute.ToolCallResult(
            content = Chunk(McpContent.text(s"${in.a + in.b}")),
            isError = false,
            structuredContent = Present(Structure.encode(Sum(in.a + in.b)))
        )
    }

    "callTool[In, Out] returns decoded Out when structuredContent is Present (INV-027)" in run {
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpError | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, addRoute),
                McpClient.initUnscoped(tc, McpInfo("typed"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                client.unsafe.callToolTypedUnsafe[AddIn, Sum]("add", AddIn(2, 3)).flatMap { result =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield assert(result == Sum(5))
                    end for
                }
            }
        }
    }

    "callTool[In, Out] returns correct value for multiple calls (INV-027)" in run {
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpError | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, addRoute),
                McpClient.initUnscoped(tc, McpInfo("typed"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                client.unsafe.callToolTypedUnsafe[AddIn, Sum]("add", AddIn(10, 20)).flatMap { r1 =>
                    client.unsafe.callToolTypedUnsafe[AddIn, Sum]("add", AddIn(0, 0)).flatMap { r2 =>
                        for
                            _ <- srv.closeNow
                            _ <- client.closeNow
                        yield
                            assert(r1 == Sum(30))
                            assert(r2 == Sum(0))
                        end for
                    }
                }
            }
        }
    }

end McpToolCallTypedTest
