package kyo.integration

import kyo.*

/** Tests §3.2 MCP 2025-06-18: when client and server versions do not overlap, the server returns
  * success with its highest supported version rather than failing.
  */
class McpProtocolVersionFallbackTest extends Test:

    "server returns highest supported version when client version is not in supported set" in {
        // Configure a server that supports a version the client does not send.
        // The client always sends McpConfig.ProtocolVersion.current ("2025-06-18").
        // Using a lexicographically later version string to guarantee a mismatch.
        // fromWire is private[kyo] and accessible from kyo.integration (a sub-package of kyo).
        val futureVersion = McpConfig.ProtocolVersion.fromWire("9999-01-01")
        val serverConfig  = McpConfig.default.withSupportedProtocolVersions(Set(futureVersion))

        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, serverConfig)(),
                McpClient.initUnscoped(tc, McpInfo("fallback-test"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                client.protocolVersion.flatMap { negotiated =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield
                        // The server fell back to "9999-01-01" (its only supported version).
                        assert(negotiated == futureVersion)
                    end for
                }
            }
        }
    }

    "server returns current version when client version matches" in {
        // Server supports only McpConfig.ProtocolVersion.current. Client sends current. They match.
        val serverConfig = McpConfig.default.withSupportedProtocolVersions(Set(McpConfig.ProtocolVersion.current))

        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, serverConfig)(),
                McpClient.initUnscoped(tc, McpInfo("normal-test"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                client.protocolVersion.flatMap { negotiated =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield assert(negotiated == McpConfig.ProtocolVersion.current)
                    end for
                }
            }
        }
    }

end McpProtocolVersionFallbackTest
