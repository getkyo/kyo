package kyo.integration

import kyo.*

/** Tests §3.8 MCP 2025-06-18: `client.ping` dispatches a `ping` request and the server handles
  * it by returning an empty response, confirming liveness.
  */
class McpPingTest extends Test:

    "client.ping returns Unit without error" in {
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts),
                McpClient.initUnscoped(tc, McpInfo("ping-test"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                client.ping.flatMap { _ =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield succeed
                    end for
                }
            }
        }
    }

    "client.ping can be called multiple times" in {
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts),
                McpClient.initUnscoped(tc, McpInfo("ping-test-multi"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                client.ping.flatMap { _ =>
                    client.ping.flatMap { _ =>
                        client.ping.flatMap { _ =>
                            for
                                _ <- srv.closeNow
                                _ <- client.closeNow
                            yield succeed
                            end for
                        }
                    }
                }
            }
        }
    }

    "client.unsafe.ping returns Unit without error" in {
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts),
                McpClient.initUnscoped(tc, McpInfo("ping-unsafe-test"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                Sync.Unsafe.defer(client.unsafe.ping.safe.get).flatMap { _ =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield succeed
                    end for
                }
            }
        }
    }

end McpPingTest
