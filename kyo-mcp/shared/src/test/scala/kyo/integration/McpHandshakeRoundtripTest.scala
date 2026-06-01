package kyo.integration

import kyo.*

/** Integration test: handshake protocol-version negotiation roundtrip (T-014, INV-002, INV-005). */
class McpHandshakeRoundtripTest extends Test:

    case class AddIn(a: Int, b: Int) derives Schema, CanEqual

    private val route1 = McpRoute.tool[AddIn]("add").handler { in =>
        McpContent.Text(s"${in.a + in.b}")
    }
    private val route2 = McpRoute.tool[AddIn]("sub").handler { in =>
        McpContent.Text(s"${in.a - in.b}")
    }
    private val clientInfo = McpInfo("calc")
    private val clientCaps = McpCapabilities.Client(sampling = Present(McpCapabilities.SamplingCapability()))

    "server.protocolVersion is Present(current) and clientInfo matches after handshake (T-014, INV-002)" in run {
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException | Closed, McpServer, McpClient, Any](
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

    "client.serverInfo and protocolVersion are Present after handshake (T-014, INV-005)" in run {
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, route1, route2),
                McpClient.initUnscoped(tc, clientInfo, clientCaps)
            ).flatMap { (srv, client) =>
                val serverInfoVal   = client.serverInfo
                val protocolVersion = client.protocolVersion
                for
                    _ <- srv.closeNow
                    _ <- client.closeNow
                yield
                    assert(serverInfoVal.isDefined)
                    assert(serverInfoVal.get.name == "kyo-mcp")
                    assert(protocolVersion == Present(McpConfig.ProtocolVersion.current))
                end for
            }
        }
    }

end McpHandshakeRoundtripTest
