package kyo.integration

import kyo.*

/** Tests for -32601 capability gating on reverse-direction methods (§3.11). */
class McpReverseDispatchCapabilityGatingTest extends Test:

    "requestSampling without client sampling capability surfaces McpCapabilityNotAdvertisedException(Peer.Client)" in {
        // Client does NOT declare sampling capability.
        // The client-side reverse dispatch returns McpCapabilityNotAdvertisedException (-32601).
        // The server preserves that -32601 and surfaces McpCapabilityNotAdvertisedException(Peer.Client).
        val clientCaps = McpCapabilities.Client()
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts),
                McpClient.initUnscoped(tc, McpInfo("gate-test"), clientCaps)
            ).flatMap { (srv, client) =>
                Abort.run[McpException](
                    srv.requestSampling(
                        McpServer.SamplingRequest(
                            messages = Chunk(McpServer.SamplingRequest.Message(
                                McpContent.Role.User,
                                McpServer.SamplingContent.Text("q")
                            )),
                            maxTokens = 10
                        )
                    )
                ).flatMap { result =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield
                        result match
                            case Result.Failure(e: McpCapabilityNotAdvertisedException) =>
                                assert(e.peer == McpCapabilityNotAdvertisedException.Peer.Client)
                            case Result.Failure(other) =>
                                fail(s"expected McpCapabilityNotAdvertisedException(Peer.Client), got: $other")
                            case Result.Success(_) =>
                                fail("expected failure when sampling capability not advertised")
                            case Result.Panic(t) =>
                                fail(s"unexpected panic: $t")
                        end match
                    end for
                }
            }
        }
    }

    "requestSampling with client sampling capability does not return -32601" in {
        // Client declares sampling capability; default handler rejects with a different error (not -32601).
        val clientCaps = McpCapabilities.Client(sampling = Present(McpCapabilities.SamplingCapability()))
        JsonRpcTransport.inMemory.flatMap { (ts, tc) =>
            Async.zip[McpException, McpServer, McpClient, Any](
                McpServer.initUnscoped(ts),
                McpClient.initUnscoped(tc, McpInfo("gate-test2"), clientCaps)
            ).flatMap { (srv, client) =>
                Abort.run[McpException](
                    srv.requestSampling(
                        McpServer.SamplingRequest(
                            messages = Chunk(McpServer.SamplingRequest.Message(
                                McpContent.Role.User,
                                McpServer.SamplingContent.Text("q")
                            )),
                            maxTokens = 10
                        )
                    )
                ).flatMap { result =>
                    for
                        _ <- srv.closeNow
                        _ <- client.closeNow
                    yield
                        result match
                            case Result.Failure(err: McpCapabilityNotAdvertisedException) =>
                                fail(s"did not expect -32601 when sampling capability is advertised")
                            case Result.Failure(_) =>
                                // Any other error is fine (e.g. sampling rejected, no handler registered)
                                succeed
                            case Result.Success(_) =>
                                succeed
                            case Result.Panic(t) =>
                                fail(s"unexpected panic: $t")
                        end match
                    end for
                }
            }
        }
    }

end McpReverseDispatchCapabilityGatingTest
