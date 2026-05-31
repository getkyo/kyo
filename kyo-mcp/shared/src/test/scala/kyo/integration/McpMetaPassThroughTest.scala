package kyo.integration

import kyo.*

/** Tests for _meta pass-through on ToolCallResult (§3.7). */
class McpMetaPassThroughTest extends Test:

    case class AddIn(a: Int, b: Int) derives Schema, CanEqual

    "ToolCallResult meta field round-trips through the wire" in run {
        val metaVal = Structure.encode(Map("echo" -> "bar"))
        val toolRoute = McpRoute.toolMulti[AddIn]("add") { (in, _) =>
            McpRoute.ToolCallResult(
                content = Chunk(McpContent.text(s"${in.a + in.b}")),
                isError = false,
                structuredContent = Absent,
                meta = Present(metaVal)
            )
        }
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, toolRoute),
                McpClient.initUnscoped(tc, McpInfo("meta-test"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                client.callTool[AddIn]("add", AddIn(2, 3)).flatMap { result =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield assert(result.meta.isDefined, s"expected meta to be Present, got ${result.meta}")
                    end for
                }
            }
        }
    }

    "ToolCallResult meta defaults to Absent when not set" in run {
        val toolRoute = McpRoute.toolMulti[AddIn]("add") { (in, _) =>
            McpRoute.ToolCallResult(
                content = Chunk(McpContent.text(s"${in.a + in.b}")),
                isError = false,
                structuredContent = Absent
            )
        }
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, toolRoute),
                McpClient.initUnscoped(tc, McpInfo("meta-test"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                client.callTool[AddIn]("add", AddIn(2, 3)).flatMap { result =>
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
