package kyo.integration

import kyo.*

/** Integration test: typed tool call aborts when structuredContent is absent. */
class McpStructuredContentMissingTest extends Test:

    case class AddIn(a: Int, b: Int) derives Schema, CanEqual
    case class Sum(value: Int) derives Schema, CanEqual

    private val textOnlyRoute = McpHandler.toolRaw[AddIn]("text-only-tool") { in =>
        McpHandler.ToolOutcome(
            content = Chunk(McpContent.text(s"${in.a + in.b}")),
            isError = false,
            structuredContent = Absent
        )
    }

    "callTool[In, Out] aborts with McpToolStructuredMissingException when structuredContent is Absent" in {
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, textOnlyRoute),
                McpClient.initUnscoped(tc, McpInfo("sc"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                Abort.run[McpException](
                    client.callTool[Sum]("text-only-tool")(AddIn(1, 1))
                ).flatMap { typedResult =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield
                        assert(typedResult.isFailure)
                        typedResult match
                            case Result.Failure(e: McpToolStructuredMissingException) =>
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

    "callToolRaw[In] returns raw ToolOutcome when structuredContent is Absent" in {
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, textOnlyRoute),
                McpClient.initUnscoped(tc, McpInfo("sc"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                client.callToolRaw[AddIn]("text-only-tool", AddIn(1, 1)).flatMap { rawResult =>
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

    // present-but-undecodable payload raises McpToolStructuredDecodeException.
    "callTool[In, Out] aborts McpToolStructuredDecodeException when structuredContent is present-but-undecodable" in {
        case class WrongShape(x: String) derives Schema, CanEqual
        val structuredRoute = McpHandler.toolRaw[AddIn]("text-only-tool") { in =>
            McpHandler.ToolOutcome(
                content = Chunk(McpContent.text(s"${in.a + in.b}")),
                isError = false,
                structuredContent = Present(Structure.encode(Sum(in.a + in.b)))
            )
        }
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, structuredRoute),
                McpClient.initUnscoped(tc, McpInfo("sc"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                Abort.run[McpException](
                    client.callTool[WrongShape]("text-only-tool")(AddIn(1, 1))
                ).flatMap { result =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield result match
                        case Result.Failure(_: McpToolStructuredDecodeException) => succeed
                        case Result.Failure(_: McpToolStructuredMissingException) =>
                            fail("expected McpToolStructuredDecodeException, got Missing")
                        case other => fail(s"expected McpToolStructuredDecodeException, got $other")
                    end for
                }
            }
        }
    }

end McpStructuredContentMissingTest
