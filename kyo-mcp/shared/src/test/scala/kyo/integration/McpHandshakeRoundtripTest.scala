package kyo.integration

import kyo.*

/** Integration test: handshake protocol-version negotiation roundtrip. */
class McpHandshakeRoundtripTest extends Test:

    case class AddIn(a: Int, b: Int) derives Schema, CanEqual

    private val route1 = McpHandler.tool[AddIn]("add") { in =>
        McpContent.Text(s"${in.a + in.b}")
    }
    private val route2 = McpHandler.tool[AddIn]("sub") { in =>
        McpContent.Text(s"${in.a - in.b}")
    }
    private val clientInfo = McpInfo("calc")
    private val clientCaps = McpCapabilities.Client(sampling = Present(McpCapabilities.SamplingCapability()))

    "server.protocolVersion is Present(current) and clientInfo matches after handshake" in {
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, route1, route2),
                McpClient.initUnscoped(tc, clientInfo, clientCaps)
            ).flatMap { (srv, client) =>
                val negotiatedVersion = srv.protocolVersion
                val negotiatedClient  = srv.clientInfo
                for
                    _ <- srv.closeNow
                    _ <- client.closeNow
                yield
                    assert(negotiatedVersion == Present(McpConfig.ProtocolVersion.current))
                    assert(negotiatedClient == Present(clientInfo))
                end for
            }
        }
    }

    "client.serverInfo and protocolVersion are populated after handshake" in {
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, route1, route2),
                McpClient.initUnscoped(tc, clientInfo, clientCaps)
            ).flatMap { (srv, client) =>
                client.serverInfo.flatMap { serverInfoVal =>
                    client.protocolVersion.flatMap { ver =>
                        for
                            _ <- srv.closeNow
                            _ <- client.closeNow
                        yield
                            assert(serverInfoVal.name == "kyo-mcp")
                            assert(ver == McpConfig.ProtocolVersion.current)
                        end for
                    }
                }
            }
        }
    }

end McpHandshakeRoundtripTest
