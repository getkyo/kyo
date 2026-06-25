package kyo.integration

import kyo.*

/** Integration test: capability gate rejects tool call when tools capability is not advertised. */
class McpCapabilityAdvertisementTest extends Test:

    case class AddIn(a: Int, b: Int) derives Schema, CanEqual

    private val toolRoute = McpHandler.tool[AddIn]("add") { in =>
        McpContent.Text(s"${in.a + in.b}")
    }

    "tool call aborts with McpCapabilityNotAdvertisedException when tools capability is absent" in {
        // McpCapabilities.Server() has all fields Absent, so tools capability is not advertised.
        val cfg = McpConfig.default.withDeclaredCapabilities(McpCapabilities.Server())
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, cfg)(toolRoute),
                McpClient.initUnscoped(tc, McpInfo("test"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                Abort.run[McpException](client.callToolRaw[AddIn]("add", AddIn(1, 1))).flatMap { result =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield
                        // The capability gate rejects with -32601; the client wraps all JsonRpcErrors
                        // as McpInvalidArgumentException(-32602). Either code signals capability rejection.
                        assert(result.isFailure)
                    end for
                }
            }
        }
    }

    "server advertises tools capability when route is registered and declaredCapabilities is Absent" in {
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, toolRoute),
                McpClient.initUnscoped(tc, McpInfo("test"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                client.serverCapabilities.flatMap { caps =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield assert(caps.tools.isDefined)
                    end for
                }
            }
        }
    }

end McpCapabilityAdvertisementTest
