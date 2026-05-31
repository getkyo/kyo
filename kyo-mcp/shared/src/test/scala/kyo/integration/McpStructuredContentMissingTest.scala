package kyo.integration

import kyo.*

/** Integration test: typed tool call aborts when structuredContent is absent (T-023, INV-027, Q-018). */
class McpStructuredContentMissingTest extends Test:

    case class AddIn(a: Int, b: Int) derives Schema, CanEqual
    case class Sum(value: Int) derives Schema, CanEqual

    private val textOnlyRoute = McpRoute.toolMulti[AddIn]("text-only-tool") { (in, _) =>
        McpRoute.ToolCallResult(
            content = Chunk(McpContent.text(s"${in.a + in.b}")),
            isError = false,
            structuredContent = Absent
        )
    }

    "callToolTyped[In, Out] aborts with McpToolStructuredMissingError when structuredContent is Absent (T-023, INV-027)" in run {
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpError | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, textOnlyRoute),
                McpClient.initUnscoped(tc, McpInfo("sc"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                Abort.run[McpError](
                    client.callToolTyped[AddIn, Sum]("text-only-tool", AddIn(1, 1))
                ).flatMap { typedResult =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield
                        assert(typedResult.isFailure)
                        typedResult match
                            case Result.Failure(e: McpToolStructuredMissingError) =>
                                assert(e.tool == "text-only-tool")
                            case Result.Failure(other) =>
                                fail(s"wrong error type: $other")
                            case _ =>
                                fail("expected Failure")
                        end match
                    end for
                }
            }
        }
    }

    "callTool[In] (untyped) returns raw ToolCallResult when structuredContent is Absent (T-023, Q-018)" in run {
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpError | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, textOnlyRoute),
                McpClient.initUnscoped(tc, McpInfo("sc"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                client.callTool[AddIn]("text-only-tool", AddIn(1, 1)).flatMap { rawResult =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield
                        assert(rawResult.structuredContent == Absent)
                        assert(rawResult.content.head == McpContent.text("2"))
                    end for
                }
            }
        }
    }

end McpStructuredContentMissingTest
