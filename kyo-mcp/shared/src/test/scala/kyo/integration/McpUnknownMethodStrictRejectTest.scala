package kyo.integration

import kyo.*

/** Integration test: unknown method is strictly rejected with MethodNotFound code (T-022, Q-016). */
class McpUnknownMethodStrictRejectTest extends Test:

    case class AddIn(a: Int, b: Int) derives Schema, CanEqual
    case class EmptyParams() derives Schema, CanEqual
    case class EmptyResult() derives Schema, CanEqual

    private val tool1 = McpRoute.tool[AddIn, McpContent.Text]("a") { (in, _) =>
        McpContent.Text(s"${in.a}", Absent)
    }
    private val tool2 = McpRoute.tool[AddIn, McpContent.Text]("b") { (in, _) =>
        McpContent.Text(s"${in.b}", Absent)
    }
    private val tool3 = McpRoute.tool[AddIn, McpContent.Text]("c") { (_, _) =>
        McpContent.Text("c", Absent)
    }

    "unknown method foo/bar is rejected with JsonRpcError code -32601 (T-022, Q-016)" in run {
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpError | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, tool1, tool2, tool3),
                McpClient.initUnscoped(tc, McpInfo("u"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                Abort.run[JsonRpcError | Closed](
                    client.underlying.call[EmptyParams, EmptyResult]("foo/bar", EmptyParams())
                ).flatMap { result =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield
                        assert(result.isFailure)
                        result match
                            case Result.Failure(e: JsonRpcError) =>
                                assert(e.code == -32601)
                            case _ => fail(s"expected JsonRpcError -32601, got $result")
                        end match
                    end for
                }
            }
        }
    }

end McpUnknownMethodStrictRejectTest
