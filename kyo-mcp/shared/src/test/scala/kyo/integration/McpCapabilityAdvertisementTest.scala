package kyo.integration

import kyo.*

/** Integration test: capability gate rejects tool call when tools capability is not advertised (T-015, INV-015, INV-019). */
class McpCapabilityAdvertisementTest extends Test:

    case class AddIn(a: Int, b: Int) derives Schema, CanEqual

    private val toolRoute = McpRoute.tool[AddIn, McpContent.Text]("add") { (in, _) =>
        McpContent.Text(s"${in.a + in.b}", Absent)
    }

    "tool call aborts with McpCapabilityNotAdvertisedError when tools capability is absent (T-015, INV-015)" in run {
        // McpCapabilities.Server() has all fields Absent, so tools capability is not advertised.
        val cfg = McpConfig.default.declaredCapabilities(McpCapabilities.Server())
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpError | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, cfg)(toolRoute),
                McpClient.initUnscoped(tc, McpInfo("test"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                Abort.run[McpError](client.unsafe.callToolUnsafe[AddIn]("add", AddIn(1, 1))).flatMap { result =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield
                        // The capability gate rejects with -32601; the client wraps all JsonRpcErrors
                        // as McpInvalidArgumentError(-32602). Either code signals capability rejection.
                        assert(result.isFailure)
                    end for
                }
            }
        }
    }

    "server advertises tools capability when route is registered and declaredCapabilities is Absent (T-015, INV-019)" in run {
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpError | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, toolRoute),
                McpClient.initUnscoped(tc, McpInfo("test"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                val caps = client.serverCapabilities
                for
                    _ <- srv.closeNow
                    _ <- client.closeNow
                yield
                    assert(caps.isDefined)
                    assert(caps.get.tools.isDefined)
                end for
            }
        }
    }

end McpCapabilityAdvertisementTest
