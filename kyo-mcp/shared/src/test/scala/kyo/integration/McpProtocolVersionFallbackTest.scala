package kyo.integration

import kyo.*

/** Tests §3.2 MCP 2025-06-18: when client and server versions do not overlap, the server returns
  * success with its highest supported version rather than failing.
  *
  * Pins acceptance criterion 2 of Phase 05 and INV-300.
  */
class McpProtocolVersionFallbackTest extends Test:

    "server returns highest supported version when client version is not in supported set" in run {
        // Configure a server that supports a version the client does not send.
        // The client always sends McpProtocolVersion.current ("2025-06-18").
        // Using a lexicographically later version string to guarantee a mismatch.
        // fromWire is private[kyo] and accessible from kyo.integration (a sub-package of kyo).
        val futureVersion = McpProtocolVersion.fromWire("9999-01-01")
        val serverConfig  = McpConfig.default.supportedProtocolVersions(Set(futureVersion))

        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpError | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, serverConfig)(),
                McpClient.initUnscoped(tc, McpInfo("fallback-test"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                val negotiated = client.protocolVersion
                for
                    _ <- srv.closeNow
                    _ <- client.closeNow
                yield
                    // The server fell back to "9999-01-01" (its only supported version).
                    assert(negotiated == Present(futureVersion))
                end for
            }
        }
    }

    "server returns current version when client version matches" in run {
        // Server supports only McpProtocolVersion.current. Client sends current. They match.
        val serverConfig = McpConfig.default.supportedProtocolVersions(Set(McpProtocolVersion.current))

        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpError | Closed, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts, serverConfig)(),
                McpClient.initUnscoped(tc, McpInfo("normal-test"), McpCapabilities.Client())
            ).flatMap { (srv, client) =>
                val negotiated = client.protocolVersion
                for
                    _ <- srv.closeNow
                    _ <- client.closeNow
                yield assert(negotiated == Present(McpProtocolVersion.current))
                end for
            }
        }
    }

end McpProtocolVersionFallbackTest
